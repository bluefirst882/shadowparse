package ai.toni.videoworkbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
  private final String coderplanApiKey;
  private final ObjectMapper json;
  private final CoderplanClient coderplan;
  private final ResultValidator resultValidator;
  private final ExecutorService queue = Executors.newSingleThreadExecutor();

  TaskService(TaskRepository tasks, ObjectMapper json, CoderplanClient coderplan, ResultValidator resultValidator, @Value("${workbench.storage-dir}") String storageDir, @Value("${workbench.ffmpeg-path}") String ffmpeg, @Value("${workbench.whisper-python}") String whisperPython, @Value("${workbench.whisper-worker}") String whisperWorker, @Value("${workbench.whisper-model}") String whisperModel, @Value("${workbench.coderplan.api-key:}") String coderplanApiKey) {
    this.tasks = tasks; this.json = json; this.coderplan = coderplan; this.resultValidator = resultValidator; this.storage = Path.of(storageDir); this.ffmpeg = ffmpeg; this.whisperPython = whisperPython; this.whisperWorker = whisperWorker; this.whisperModel = whisperModel; this.coderplanApiKey = coderplanApiKey;
  }

  List<VideoTask> list() { return tasks.all(); }
  VideoTask get(String id) { return tasks.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在")); }
  TaskDetails details(String id) { return new TaskDetails(get(id), tasks.segments(id), tasks.result(id).orElse(null)); }
  FileSystemResource video(String id) { return new FileSystemResource(get(id).videoPath()); }
  VideoTask importVideo(MultipartFile file) {
    if (file.isEmpty() || !isVideo(file.getContentType(), file.getOriginalFilename())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的视频文件");
    try {
      Files.createDirectories(storage); String id = UUID.randomUUID().toString(); Path target = storage.resolve(id + extension(file.getOriginalFilename()));
      file.transferTo(target); Instant now = Instant.now(); VideoTask task = new VideoTask(id, safeName(file.getOriginalFilename()), target.toAbsolutePath().toString(), Files.size(target), TaskStatus.QUEUED, TaskStage.IMPORT, 0, null, now, now);
      tasks.save(task); enqueue(id); return task;
    } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存视频文件"); }
  }
  void cancel(String id) { get(id); tasks.cancel(id); }
  void retry(String id) { VideoTask task = get(id); if (task.status() == TaskStatus.PROCESSING) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在处理"); tasks.reset(id, task.stage() == TaskStage.SUMMARY || task.stage() == TaskStage.COMPLETED ? TaskStage.SUMMARY : TaskStage.IMPORT); enqueue(id); }
  void delete(String id) { VideoTask task = get(id); try { Files.deleteIfExists(Path.of(task.videoPath())); } catch (IOException ignored) { } tasks.delete(id); }
  private void enqueue(String id) { queue.submit(() -> process(id)); }
  private void process(String id) {
    VideoTask task = get(id); TaskStage currentStage = task.stage(); try {
      if (currentStage == TaskStage.SUMMARY) { generateContent(id); return; }
      checkCancelled(id); tasks.update(id, TaskStatus.PROCESSING, TaskStage.AUDIO_EXTRACTION, 10, null);
      currentStage = TaskStage.AUDIO_EXTRACTION;
      Path audio = storage.resolve(id + ".wav"); run(List.of(ffmpeg, "-y", "-i", task.videoPath(), "-vn", "-ac", "1", "-ar", "16000", audio.toString()));
      checkCancelled(id); tasks.update(id, TaskStatus.PROCESSING, TaskStage.TRANSCRIPTION, 45, null);
      currentStage = TaskStage.TRANSCRIPTION;
      // Worker emits JSON to stdout; production parser persists each timestamped segment here.
      String output = run(List.of(whisperPython, whisperWorker, audio.toString(), "--model", whisperModel));
      persistTranscript(id, output);
      checkCancelled(id); tasks.update(id, TaskStatus.COMPLETED, TaskStage.SUMMARY, 100, "本地转写已完成；请配置摘要服务后单独重试内容生成");
    } catch (Cancelled ignored) { tasks.update(id, TaskStatus.CANCELLED, currentStage, 0, null); }
      catch (Exception ex) { tasks.update(id, TaskStatus.FAILED, currentStage, 0, "本地处理失败，请检查 FFmpeg、Whisper 与视频文件后重试"); }
  }
  private void generateContent(String id) { if (coderplanApiKey.isBlank() || !coderplan.configured()) { tasks.update(id, TaskStatus.COMPLETED, TaskStage.SUMMARY, 100, "未配置 CODERPLAN_API_KEY；本地转写已保留，可配置后重试。"); return; } List<TranscriptSegment> transcript = tasks.segments(id); if (transcript.isEmpty()) { tasks.update(id, TaskStatus.FAILED, TaskStage.SUMMARY, 100, "没有可用于内容生成的转写结果。"); return; } try { tasks.update(id, TaskStatus.PROCESSING, TaskStage.SUMMARY, 85, null); TaskResult result = coderplan.summarize(transcript); resultValidator.validate(result, transcript); tasks.saveResult(id, result); tasks.update(id, TaskStatus.COMPLETED, TaskStage.COMPLETED, 100, null); } catch (Exception ex) { tasks.update(id, TaskStatus.FAILED, TaskStage.SUMMARY, 100, "内容生成失败，本地转写已保留，可稍后重试。"); } }
  private String run(List<String> command) throws IOException, InterruptedException { Process process = new ProcessBuilder(command).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes()); if (!process.waitFor(30, java.util.concurrent.TimeUnit.MINUTES) || process.exitValue() != 0) throw new IOException("外部进程失败"); return output; }
  private void persistTranscript(String id, String output) throws IOException { JsonNode segments = json.readTree(output).path("segments"); if (!segments.isArray() || segments.isEmpty()) throw new IOException("Whisper 未返回有效转写"); java.util.ArrayList<TranscriptSegment> saved = new java.util.ArrayList<>(); for (JsonNode segment : segments) { long start = Math.round(segment.path("start").asDouble() * 1000); long end = Math.round(segment.path("end").asDouble() * 1000); String text = segment.path("text").asText().trim(); if (end > start && !text.isBlank()) saved.add(new TranscriptSegment(0, start, end, text)); } if (saved.isEmpty()) throw new IOException("转写内容为空"); tasks.replaceSegments(id, saved); }
  private void checkCancelled(String id) { if (tasks.cancelled(id)) throw new Cancelled(); }
  private boolean isVideo(String type, String name) { return type != null && type.startsWith("video/") || name != null && name.matches("(?i).*\\.(mp4|mov|mkv|webm|avi)$"); }
  private String extension(String name) { int index = name == null ? -1 : name.lastIndexOf('.'); return index < 0 ? ".mp4" : name.substring(index); }
  private String safeName(String name) { return name == null ? "未命名视频" : Path.of(name).getFileName().toString(); }
  private static class Cancelled extends RuntimeException { }
}
