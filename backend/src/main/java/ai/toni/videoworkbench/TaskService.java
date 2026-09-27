package ai.toni.videoworkbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class TaskService {
  private static final Logger log = LoggerFactory.getLogger(TaskService.class);
  private static final int SUMMARY_ATTEMPTS = 3;

  private final TaskRepository tasks;
  private final Path storage;
  private final String ffmpeg;
  private final String whisperServiceUrl;
  private final String whisperServiceToken;
  private final String whisperModel;
  private final String llmApiKey;
  private final ObjectMapper json;
  private final LlmClient llm;
  private final ResultValidator resultValidator;
  private final WorkbenchMetrics metrics;
  private final long maxUploadBytes;
  private final long timeoutMinutes;
  private final int summaryMaxInputChars;
  private final TaskQueue queue;
  private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

  TaskService(
      TaskRepository tasks,
      ObjectMapper json,
      LlmClient llm,
      ResultValidator resultValidator,
      WorkbenchMetrics metrics,
      @Value("${workbench.storage-dir}") String storageDir,
      @Value("${workbench.ffmpeg-path}") String ffmpeg,
      @Value("${workbench.whisper-service-url}") String whisperServiceUrl,
      @Value("${workbench.whisper-service-token:}") String whisperServiceToken,
      @Value("${workbench.whisper-model}") String whisperModel,
      @Value("${workbench.llm.api-key:}") String llmApiKey,
      @Value("${workbench.max-upload-bytes}") long maxUploadBytes,
      @Value("${workbench.process-timeout-minutes}") long timeoutMinutes,
      @Value("${workbench.llm.max-input-chars:60000}") int summaryMaxInputChars,
      TaskQueue queue) {
    this.tasks = tasks;
    this.json = json;
    this.llm = llm;
    this.resultValidator = resultValidator;
    this.metrics = metrics;
    this.storage = Path.of(storageDir);
    this.ffmpeg = ffmpeg;
    this.whisperServiceUrl = whisperServiceUrl;
    this.whisperServiceToken = whisperServiceToken;
    this.whisperModel = whisperModel;
    this.llmApiKey = llmApiKey;
    this.maxUploadBytes = maxUploadBytes;
    this.timeoutMinutes = timeoutMinutes;
    this.summaryMaxInputChars = summaryMaxInputChars;
    this.queue = queue;
  }

  TaskPage list(String ownerId, TaskCursor cursor, int limit) {
    // 多取一条，只为判断是否还有下一页，不返回给客户端。
    List<VideoTask> rows = tasks.page(ownerId, cursor, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<VideoTask> items = List.copyOf(rows.subList(0, hasMore ? limit : rows.size()));
    String nextCursor = hasMore ? cursorOf(items.get(items.size() - 1)) : null;
    return new TaskPage(items, nextCursor);
  }

  private static String cursorOf(VideoTask task) {
    return new TaskCursor(task.createdAt(), task.id()).encode();
  }

  VideoTask get(String id, String ownerId) {
    return tasks.findOwned(id, ownerId).orElseThrow(() -> new AccessDeniedException("无权访问该任务"));
  }

  TaskDetails details(String id, String ownerId) {
    return new TaskDetails(get(id, ownerId), tasks.segments(id), tasks.result(id).orElse(null));
  }

  FileSystemResource video(String id, String ownerId) {
    return new FileSystemResource(get(id, ownerId).videoPath());
  }

  VideoTask importVideo(MultipartFile file, String ownerId) {
    if (file.isEmpty() || !isVideo(file.getContentType(), file.getOriginalFilename()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的视频文件");
    if (file.getSize() > maxUploadBytes)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "视频文件超过允许大小");
    Path target = null;
    boolean persisted = false;
    try {
      Files.createDirectories(storage);
      String id = UUID.randomUUID().toString();
      target = storage.resolve(id + extension(file.getOriginalFilename()));
      file.transferTo(target);
      Instant now = Instant.now();
      VideoTask task =
          new VideoTask(
              id,
              safeName(file.getOriginalFilename()),
              target.toAbsolutePath().toString(),
              Files.size(target),
              TaskStatus.QUEUED,
              TaskStage.IMPORT,
              0,
              null,
              now,
              now);
      tasks.save(task, ownerId);
      persisted = true;
      try {
        queue.publish(id);
        return task;
      } catch (TaskQueue.UnavailableException ex) {
        // 队列不可用时任务留在 QUEUED：库里已有记录，broker 恢复后可重试或由启动恢复重投，
        // 不能因为投递失败就把刚上传的视频判成失败。
        String message = "消息队列暂不可用，任务已保存，可稍后重试";
        tasks.update(id, TaskStatus.QUEUED, TaskStage.IMPORT, 0, message);
        return new VideoTask(
            task.id(),
            task.fileName(),
            task.videoPath(),
            task.sizeBytes(),
            task.status(),
            task.stage(),
            task.progress(),
            message,
            task.createdAt(),
            Instant.now());
      }
    } catch (IOException ex) {
      deleteIfExists(target);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存视频文件");
    } catch (RuntimeException ex) {
      // A database failure must not leave an uploaded file without a task record.
      if (!persisted) deleteIfExists(target);
      throw ex;
    }
  }

  void cancel(String id, String ownerId) {
    get(id, ownerId);
    if (!tasks.cancel(id))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只能取消排队中或正在处理的任务");
    Process process = runningProcesses.remove(id);
    if (process != null) process.destroyForcibly();
  }

  void retry(String id, String ownerId) {
    VideoTask task = get(id, ownerId);
    if (task.status() == TaskStatus.PROCESSING)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已在队列中或正在处理");
    if (task.status() == TaskStatus.QUEUED) {
      enqueue(id);
      return;
    }
    tasks.reset(
        id,
        task.stage() == TaskStage.SUMMARY || task.stage() == TaskStage.COMPLETED
            ? TaskStage.SUMMARY
            : TaskStage.IMPORT);
    enqueue(id);
  }

  void retranscribe(String id, String ownerId) {
    VideoTask task = get(id, ownerId);
    if (task.status() == TaskStatus.PROCESSING)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已在队列中或正在处理");
    if (task.status() == TaskStatus.QUEUED) {
      enqueue(id);
      return;
    }
    tasks.reset(id, TaskStage.IMPORT);
    tasks.deleteResult(id);
    enqueue(id);
  }

  void delete(String id, String ownerId) {
    VideoTask task = get(id, ownerId);
    Process process = runningProcesses.remove(id);
    if (process != null) process.destroyForcibly();
    IOException cleanupFailure = null;
    try {
      Files.deleteIfExists(Path.of(task.videoPath()));
    } catch (IOException ignored) {
      cleanupFailure = ignored;
    }
    try {
      Files.deleteIfExists(audioPath(id));
    } catch (IOException ex) {
      if (cleanupFailure == null) cleanupFailure = ex;
    }
    if (cleanupFailure != null) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "本地文件清理失败，任务记录已保留");
    }
    tasks.delete(id);
  }

  /** 投递任务；broker 不可用时明确失败，不假装已经入队。重复投递由领取时的原子更新兜底。 */
  private void enqueue(String id) {
    try {
      queue.publish(id);
    } catch (TaskQueue.UnavailableException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "消息队列暂不可用，请稍后重试", ex);
    }
  }

  @PreDestroy
  void stop() {
    runningProcesses.values().forEach(Process::destroyForcibly);
    runningProcesses.clear();
  }

  void recoverAfterRestart() {
    tasks.markProcessingAsQueued();
    tasks
        .recoverable()
        .forEach(
            task -> {
              try {
                enqueue(task.id());
              } catch (ResponseStatusException ignored) {
                // broker 暂不可用：任务保持 QUEUED，恢复后可由用户重试或下次启动时重投。
              }
            });
  }

  /**
   * 处理单个任务。由队列消费者调用（{@link TaskQueueConsumer}），不直接暴露给 HTTP 调用方。
   *
   * <p>领取任务是幂等的关键：只有把任务从 {@code QUEUED} 原子改成 {@code PROCESSING} 成功的那个消费者才会往下执行， 因此同一条消息被重复投递（broker
   * 重投、用户反复点重试）也只会真正处理一次。
   *
   * <p>失败分两类：可重试的瞬时故障（Whisper 不可达、数据库不可达等）抛 {@link TransientFailure}，任务放回 {@code QUEUED}
   * 由消费者安排退避重试；其余失败直接落 {@code FAILED}，重试多少次都是同样的结果，不值得占用重试额度。
   *
   * @param attempt 本次是第几次尝试，取自重试消息头；首次投递为 1
   */
  void process(String id, int attempt) {
    VideoTask task = tasks.find(id).orElse(null);
    if (task == null) {
      log.info("任务已不存在，丢弃投递：taskId={}", id);
      return;
    }
    if (attempt > 1 && task.status() == TaskStatus.PROCESSING) {
      // 这条消息就是它自己的重试投递，而任务还停在 PROCESSING：说明上一次尝试被基础设施故障打断、
      // 没来得及改状态。先放回 QUEUED 才能重新领取，否则这次重试会被当成重复投递白白丢掉。
      log.warn("重试投递发现任务仍是 PROCESSING，先放回 QUEUED：taskId={} attempt={}", id, attempt);
      tasks.releaseProcessing(id);
    }
    if (!tasks.claimForProcessing(id)) {
      // 重复投递（broker 重投、用户连点重试）只会走到这里：原子领取失败即说明别的消费者已经在处理，
      // 或任务已经结束，直接确认掉这条消息即可。
      log.info("任务已被领取或已结束，跳过重复投递：taskId={}", id);
      return;
    }
    TaskStage currentStage = task.stage();
    try {
      if (currentStage == TaskStage.SUMMARY) {
        generateContent(id);
        return;
      }
      checkCancelled(id);
      tasks.update(id, TaskStatus.PROCESSING, TaskStage.AUDIO_EXTRACTION, 10, null);
      currentStage = TaskStage.AUDIO_EXTRACTION;
      Path audio = audioPath(id);
      run(
          id,
          List.of(
              ffmpeg,
              "-y",
              "-i",
              task.videoPath(),
              "-vn",
              "-ac",
              "1",
              "-ar",
              "16000",
              audio.toString()));
      checkCancelled(id);
      tasks.update(id, TaskStatus.PROCESSING, TaskStage.TRANSCRIPTION, 45, null);
      currentStage = TaskStage.TRANSCRIPTION;
      // Worker emits JSON to stdout; production parser persists each timestamped segment here.
      transcribe(id, audio);
      checkCancelled(id);
      // 转写完成后直接串联内容生成，无需用户手动触发。
      generateContent(id);
    } catch (Cancelled ignored) {
      tasks.update(id, TaskStatus.CANCELLED, currentStage, 0, null);
      deleteAudio(id);
    } catch (TransientFailure ex) {
      // 瞬时故障：任务放回 QUEUED 等下一次尝试。留成 PROCESSING 的话，重试投递会因为领不到任务而被白白确认。
      String detail =
          ex.getMessage() == null ? ex.getClass().getSimpleName() : tail(ex.getMessage());
      log.warn("任务处理遇到瞬时故障，等待退避重试：taskId={} attempt={} reason={}", id, attempt, ex.reason());
      tasks.update(
          id, TaskStatus.QUEUED, currentStage, 0, "第 " + attempt + " 次尝试遇到瞬时故障，稍后自动重试：" + detail);
      throw ex;
    } catch (DataAccessException ex) {
      // 数据库不可达：此刻连「改成 FAILED」都写不进去，交给消费者按瞬时故障处理（退避重试）。
      throw ex;
    } catch (Exception ex) {
      String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      tasks.update(id, TaskStatus.FAILED, currentStage, 0, "本地处理失败：" + tail(detail));
    }
  }

  /** 自动重试次数用尽后把任务判为失败，保留原因供用户判断是否人工重投（重新点「重试」会重新投递并从第 1 次算起）。 */
  void markRetryExhausted(String taskId, int attempts, String reason, String detail) {
    VideoTask task = tasks.find(taskId).orElse(null);
    if (task == null
        || task.status() == TaskStatus.COMPLETED
        || task.status() == TaskStatus.CANCELLED) return;
    tasks.update(
        taskId,
        TaskStatus.FAILED,
        task.stage(),
        0,
        "自动重试 " + attempts + " 次仍失败（" + reason + "）：" + tail(detail));
  }

  private void generateContent(String id) {
    if (llmApiKey.isBlank() || !llm.configured()) {
      tasks.update(
          id, TaskStatus.COMPLETED, TaskStage.SUMMARY, 100, "未配置 LLM_API_KEY；本地转写已保留，可配置后重试。");
      return;
    }
    List<TranscriptSegment> transcript = tasks.segments(id);
    if (transcript.isEmpty()) {
      tasks.update(id, TaskStatus.FAILED, TaskStage.SUMMARY, 100, "没有可用于内容生成的转写结果。");
      return;
    }
    try {
      tasks.update(id, TaskStatus.PROCESSING, TaskStage.SUMMARY, 85, null);
      // 云端模型输出不稳定，校验不通过时自动重新生成，最多 SUMMARY_ATTEMPTS 次。
      // 重试时把上一次的失败原因回灌提示词：同一个提示词重试大概率仍产出同样的幻觉，带上原因才有意义。
      TaskResult result = null;
      Exception failure = null;
      for (int attempt = 0; attempt < SUMMARY_ATTEMPTS && result == null; attempt++) {
        if (attempt > 0) metrics.recordLlmRetry("summarize");
        try {
          TaskResult candidate =
              llm.summarize(
                  id,
                  transcript,
                  summaryMaxInputChars,
                  failure == null ? null : failure.getMessage());
          checkCancelled(id);
          resultValidator.validate(candidate, transcript);
          result = candidate;
        } catch (Cancelled ex) {
          throw ex;
        } catch (TransientFailure ex) {
          // 瞬时故障重试多少次同一个提示词也没用，立刻上抛交给退避重试，不要消耗这里的 3 次额度。
          throw ex;
        } catch (ResultValidator.Failure ex) {
          metrics.recordLlmValidationFailure(ex.reason());
          failure = ex;
        } catch (Exception ex) {
          failure = ex;
        }
      }
      if (result == null) throw failure;
      tasks.saveResult(id, result);
      checkCancelled(id);
      tasks.update(id, TaskStatus.COMPLETED, TaskStage.COMPLETED, 100, null);
    } catch (Cancelled ignored) {
      tasks.update(id, TaskStatus.CANCELLED, TaskStage.SUMMARY, 0, null);
    } catch (TransientFailure ex) {
      throw ex;
    } catch (Exception ex) {
      String detail =
          ex.getMessage() == null ? ex.getClass().getSimpleName() : tail(ex.getMessage());
      tasks.update(
          id, TaskStatus.FAILED, TaskStage.SUMMARY, 100, "内容生成失败：" + detail + "；本地转写已保留，可稍后重试。");
    }
  }

  private String run(String id, List<String> command) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().put("PYTHONIOENCODING", "utf-8");
    Process process = builder.start();
    runningProcesses.put(id, process);
    CompletableFuture<String> output = read(process.getInputStream());
    CompletableFuture<String> error = read(process.getErrorStream());
    try {
      if (!process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IOException("外部进程超时");
      }
      if (tasks.cancelled(id)) throw new Cancelled();
      String result = output.join();
      String diagnostic = error.join();
      if (process.exitValue() != 0) throw new IOException("外部进程失败：" + tail(diagnostic));
      return result;
    } finally {
      runningProcesses.remove(id, process);
    }
  }

  private CompletableFuture<String> read(java.io.InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
        });
  }

  private String tail(String output) {
    String compact = output == null ? "" : output.replaceAll("\\s+", " ").trim();
    return compact.length() <= 240 ? compact : compact.substring(compact.length() - 240);
  }

  private void transcribe(String id, Path audio) throws Exception {
    Instant startedAt = Instant.now();
    boolean success = false;
    try {
      persistTranscript(id, runWhisper(id, audio));
      success = true;
    } finally {
      metrics.recordTranscription(Duration.between(startedAt, Instant.now()), success);
    }
  }

  private String runWhisper(String id, Path audio) throws IOException, InterruptedException {
    if (whisperServiceToken.isBlank()) throw new IOException("WHISPER_SERVICE_TOKEN 未配置");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(whisperServiceUrl + "/transcribe"))
            .timeout(java.time.Duration.ofMinutes(timeoutMinutes))
            .header("Content-Type", "audio/wav")
            .header("X-Whisper-Token", whisperServiceToken)
            .header("X-Whisper-Model", whisperModel)
            .POST(HttpRequest.BodyPublishers.ofFile(audio))
            .build();
    HttpResponse<String> response;
    try {
      response =
          HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(10))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException ex) {
      // 连接被拒、读超时：Whisper worker 可能只是重启中或显存被占满，属于「等一会儿会好」的故障。
      throw new TransientFailure(
          TransientFailure.WHISPER_UNAVAILABLE,
          "Whisper 服务不可达："
              + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
          ex);
    }
    if (tasks.cancelled(id)) throw new Cancelled();
    if (response.statusCode() >= 500)
      throw new TransientFailure(
          TransientFailure.WHISPER_UNAVAILABLE,
          "Whisper 服务返回 " + response.statusCode() + "：" + tail(response.body()));
    if (response.statusCode() / 100 != 2)
      // 4xx 是请求或音频本身不被接受，重试同样会失败，按普通失败处理。
      throw new IOException("Whisper 服务失败：" + tail(response.body()));
    return response.body();
  }

  private void persistTranscript(String id, String output) throws Exception {
    JsonNode root = json.readTree(output);
    JsonNode segments = root.path("segments");
    if (!segments.isArray() || segments.isEmpty()) throw new IOException("Whisper 未返回有效转写");
    java.util.ArrayList<TranscriptSegment> saved = new java.util.ArrayList<>();
    for (JsonNode segment : segments) {
      long start = Math.round(segment.path("start").asDouble() * 1000);
      long end = Math.round(segment.path("end").asDouble() * 1000);
      String text = segment.path("text").asText().trim();
      if (end > start && !text.isBlank())
        saved.add(new TranscriptSegment(0, start, end, text, null));
    }
    if (saved.isEmpty()) throw new IOException("转写内容为空");
    tasks.replaceSegments(id, saved);
    if (!root.path("language").asText().startsWith("zh") && llm.configured())
      tasks.replaceTranslations(id, llm.translateToChinese(id, tasks.segments(id)));
  }

  private void checkCancelled(String id) {
    if (tasks.cancelled(id)) throw new Cancelled();
  }

  private Path audioPath(String id) {
    return storage.resolve(id + ".wav");
  }

  private void deleteAudio(String id) {
    try {
      Files.deleteIfExists(audioPath(id));
    } catch (IOException ignored) {
      // Cancellation state is still durable even if an OS file lock delays cleanup.
    }
  }

  private void deleteIfExists(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup after a failed import.
    }
  }

  private boolean isVideo(String type, String name) {
    return type != null && type.startsWith("video/")
        || name != null && name.matches("(?i).*\\.(mp4|mov|mkv|webm|avi)$");
  }

  private String extension(String name) {
    int index = name == null ? -1 : name.lastIndexOf('.');
    return index < 0 ? ".mp4" : name.substring(index);
  }

  private String safeName(String name) {
    return name == null ? "未命名视频" : Path.of(name).getFileName().toString();
  }

  private static class Cancelled extends RuntimeException {}
}
