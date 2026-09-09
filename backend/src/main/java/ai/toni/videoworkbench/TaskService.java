package ai.toni.videoworkbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class TaskService {
  private final TaskRepository tasks;
  private final Path storage;
  private final String ffmpeg;
  private final String whisperPython;
  private final String whisperWorker;
  private final String whisperModel;
  private final String whisperModelDir;
  private final String coderplanApiKey;
  private final ObjectMapper json;
  private final CoderplanClient coderplan;
  private final ResultValidator resultValidator;
  private final long maxUploadBytes;
  private final long timeoutMinutes;
  private final ExecutorService queue = Executors.newSingleThreadExecutor();
  private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

  TaskService(TaskRepository tasks, ObjectMapper json, CoderplanClient coderplan, ResultValidator resultValidator, @Value("${workbench.storage-dir}") String storageDir, @Value("${workbench.ffmpeg-path}") String ffmpeg, @Value("${workbench.whisper-python}") String whisperPython, @Value("${workbench.whisper-worker}") String whisperWorker, @Value("${workbench.whisper-model}") String whisperModel, @Value("${workbench.whisper-model-dir}") String whisperModelDir, @Value("${workbench.coderplan.api-key:}") String coderplanApiKey, @Value("${workbench.max-upload-bytes}") long maxUploadBytes, @Value("${workbench.process-timeout-minutes}") long timeoutMinutes) {
    this.tasks = tasks; this.json = json; this.coderplan = coderplan; this.resultValidator = resultValidator; this.storage = Path.of(storageDir); this.ffmpeg = ffmpeg; this.whisperPython = whisperPython; this.whisperWorker = whisperWorker; this.whisperModel = whisperModel; this.whisperModelDir = whisperModelDir; this.coderplanApiKey = coderplanApiKey; this.maxUploadBytes = maxUploadBytes; this.timeoutMinutes = timeoutMinutes;
  }

  List<VideoTask> list() { return tasks.all(); }
  VideoTask get(String id) { return tasks.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在")); }
  TaskDetails details(String id) { return new TaskDetails(get(id), tasks.segments(id), tasks.result(id).orElse(null)); }
  FileSystemResource video(String id) { return new FileSystemResource(get(id).videoPath()); }
  VideoTask importVideo(MultipartFile file) {
    if (file.isEmpty() || !isVideo(file.getContentType(), file.getOriginalFilename())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的视频文件");
    if (file.getSize() > maxUploadBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "视频文件超过允许大小");
    try {
      Files.createDirectories(storage); String id = UUID.randomUUID().toString(); Path target = storage.resolve(id + extension(file.getOriginalFilename()));
      file.transferTo(target); Instant now = Instant.now(); VideoTask task = new VideoTask(id, safeName(file.getOriginalFilename()), target.toAbsolutePath().toString(), Files.size(target), TaskStatus.QUEUED, TaskStage.IMPORT, 0, null, now, now);
      tasks.save(task); enqueue(id); return task;
    } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存视频文件"); }
  }
  void cancel(String id) { get(id); tasks.cancel(id); Process process = runningProcesses.remove(id); if (process != null) process.destroyForcibly(); }
  void retry(String id) { VideoTask task = get(id); if (task.status() == TaskStatus.PROCESSING) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在处理"); tasks.reset(id, task.stage() == TaskStage.SUMMARY || task.stage() == TaskStage.COMPLETED ? TaskStage.SUMMARY : TaskStage.IMPORT); enqueue(id); }
  void retranscribe(String id) { VideoTask task = get(id); if (task.status() == TaskStatus.PROCESSING) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在处理"); tasks.reset(id, TaskStage.IMPORT); enqueue(id); }
  void delete(String id) {
    VideoTask task = get(id);
    Process process = runningProcesses.remove(id);
    if (process != null) process.destroyForcibly();
    try { Files.deleteIfExists(Path.of(task.videoPath())); } catch (IOException ignored) { }
    tasks.delete(id);
  }
  private void enqueue(String id) { queue.submit(() -> process(id)); }
  void recoverAfterRestart() { tasks.markProcessingAsQueued(); tasks.recoverable().forEach(task -> enqueue(task.id())); }
  private void process(String id) {
    VideoTask task = get(id); TaskStage currentStage = task.stage(); try {
      if (currentStage == TaskStage.SUMMARY) { generateContent(id); return; }
      checkCancelled(id); tasks.update(id, TaskStatus.PROCESSING, TaskStage.AUDIO_EXTRACTION, 10, null);
      currentStage = TaskStage.AUDIO_EXTRACTION;
      Path audio = storage.resolve(id + ".wav"); run(id, List.of(ffmpeg, "-y", "-i", task.videoPath(), "-vn", "-ac", "1", "-ar", "16000", audio.toString()));
      checkCancelled(id); tasks.update(id, TaskStatus.PROCESSING, TaskStage.TRANSCRIPTION, 45, null);
      currentStage = TaskStage.TRANSCRIPTION;
      // Worker emits JSON to stdout; production parser persists each timestamped segment here.
      String output = run(id, List.of(whisperPython, whisperWorker, audio.toString(), "--model", whisperModel, "--model-dir", whisperModelDir));
      persistTranscript(id, output);
      checkCancelled(id); tasks.update(id, TaskStatus.COMPLETED, TaskStage.SUMMARY, 100, "本地转写已完成；请配置摘要服务后单独重试内容生成");
    } catch (Cancelled ignored) { tasks.update(id, TaskStatus.CANCELLED, currentStage, 0, null); }
      catch (Exception ex) { String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); tasks.update(id, TaskStatus.FAILED, currentStage, 0, "本地处理失败：" + tail(detail)); }
  }
  private void generateContent(String id) {
    if (coderplanApiKey.isBlank() || !coderplan.configured()) { tasks.update(id, TaskStatus.COMPLETED, TaskStage.SUMMARY, 100, "未配置 CODERPLAN_API_KEY；本地转写已保留，可配置后重试。"); return; }
    List<TranscriptSegment> transcript = tasks.segments(id);
    if (transcript.isEmpty()) { tasks.update(id, TaskStatus.FAILED, TaskStage.SUMMARY, 100, "没有可用于内容生成的转写结果。"); return; }
    try {
      tasks.update(id, TaskStatus.PROCESSING, TaskStage.SUMMARY, 85, null);
      TaskResult result = coderplan.summarize(transcript);
      checkCancelled(id);
      resultValidator.validate(result, transcript);
      tasks.saveResult(id, result);
      checkCancelled(id);
      tasks.update(id, TaskStatus.COMPLETED, TaskStage.COMPLETED, 100, null);
    } catch (Cancelled ignored) { tasks.update(id, TaskStatus.CANCELLED, TaskStage.SUMMARY, 0, null);
    } catch (Exception ex) { tasks.update(id, TaskStatus.FAILED, TaskStage.SUMMARY, 100, "内容生成失败，本地转写已保留，可稍后重试。"); }
  }
  private String run(String id, List<String> command) throws IOException, InterruptedException { ProcessBuilder builder = new ProcessBuilder(command); builder.environment().put("PYTHONIOENCODING", "utf-8"); Process process = builder.start(); runningProcesses.put(id, process); CompletableFuture<String> output = read(process.getInputStream()); CompletableFuture<String> error = read(process.getErrorStream()); try { if (!process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IOException("外部进程超时"); } if (tasks.cancelled(id)) throw new Cancelled(); String result = output.join(); String diagnostic = error.join(); if (process.exitValue() != 0) throw new IOException("外部进程失败：" + tail(diagnostic)); return result; } finally { runningProcesses.remove(id, process); } }
  private CompletableFuture<String> read(java.io.InputStream stream) { return CompletableFuture.supplyAsync(() -> { try { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); } catch (IOException ex) { throw new UncheckedIOException(ex); } }); }
  private String tail(String output) { String compact = output == null ? "" : output.replaceAll("\\s+", " ").trim(); return compact.length() <= 240 ? compact : compact.substring(compact.length() - 240); }
  private void persistTranscript(String id, String output) throws Exception { JsonNode root = json.readTree(output); JsonNode segments = root.path("segments"); if (!segments.isArray() || segments.isEmpty()) throw new IOException("Whisper 未返回有效转写"); java.util.ArrayList<TranscriptSegment> saved = new java.util.ArrayList<>(); for (JsonNode segment : segments) { long start = Math.round(segment.path("start").asDouble() * 1000); long end = Math.round(segment.path("end").asDouble() * 1000); String text = segment.path("text").asText().trim(); if (end > start && !text.isBlank()) saved.add(new TranscriptSegment(0, start, end, text, null)); } if (saved.isEmpty()) throw new IOException("转写内容为空"); tasks.replaceSegments(id, saved); if (!root.path("language").asText().startsWith("zh") && coderplan.configured()) tasks.replaceTranslations(id, coderplan.translateToChinese(tasks.segments(id))); }
  private void checkCancelled(String id) { if (tasks.cancelled(id)) throw new Cancelled(); }
  private boolean isVideo(String type, String name) { return type != null && type.startsWith("video/") || name != null && name.matches("(?i).*\\.(mp4|mov|mkv|webm|avi)$"); }
  private String extension(String name) { int index = name == null ? -1 : name.lastIndexOf('.'); return index < 0 ? ".mp4" : name.substring(index); }
  private String safeName(String name) { return name == null ? "未命名视频" : Path.of(name).getFileName().toString(); }
  private static class Cancelled extends RuntimeException { }
}
