package ai.toni.videoworkbench;

import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 视觉分支：场景检测抽关键帧 → pHash 相邻去重 → OCR 回填文本。
 *
 * <p>执行模型（见 docs/改造方案与优先级.md P1）：抽帧在 {@code VISUAL_EXTRACTION} 阶段同步完成； OCR 是 CPU 密集而 Whisper 是 GPU
 * 密集，所以 OCR 被丢进独立的单线程池，在 {@code TRANSCRIPTION} 阶段与转写并行执行——两路互不抢资源，任何一路失败都保留另一路的成果。
 *
 * <p>降级语义：OCR 整路失败只记 WARN，帧行保留（缩略图仍可展示），产物视为「无画面文本」； 抽帧失败同样不阻断主链路（语音转写不依赖画面），任务继续。
 */
@Service
class VisualExtractionService {
  private static final Logger log = LoggerFactory.getLogger(VisualExtractionService.class);

  /** showinfo 打印的帧时间戳（秒）。 */
  private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9.]+)");

  /** 单点 seek 抽帧的时限：正常一两秒就该完成，超时视为该点失败并跳过。 */
  private static final long SEEK_TIMEOUT_SECONDS = 60;

  /** 单任务关键帧上限：防止高切换率视频生成几百张帧图拖垮 OCR 与磁盘。 */
  private final int maxFrames;

  /** 场景变化检测阈值：高于此值判定为画面切换（0.3 是 ffmpeg 场景检测的常用起点）。 */
  private final double sceneThreshold;

  /** 保底采样间隔（秒）：静态板书没有场景切换，靠它保证每 30s 至少一帧。 */
  private final int fallbackSamplingSeconds;

  private final String ffmpeg;
  private final Path storage;
  private final OcrEngine ocr;
  private final FrameRepository frames;
  private final WorkbenchMetrics metrics;
  private final long timeoutMinutes;
  // OCR 挂在单线程池上：消费并发固定为 1，同一时刻只有一个任务在跑 OCR，多线程没有收益。
  private final ExecutorService ocrExecutor =
      Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "ocr-worker"));
  // 抽帧的 ffmpeg 进程按任务登记，支持用户取消时立刻杀掉（与 TaskService.runningProcesses 同一模式）。
  private final java.util.concurrent.ConcurrentHashMap<String, Process> runningExtractors =
      new java.util.concurrent.ConcurrentHashMap<>();

  VisualExtractionService(
      FrameRepository frames,
      OcrEngine ocr,
      WorkbenchMetrics metrics,
      @Value("${workbench.ffmpeg-path}") String ffmpeg,
      @Value("${workbench.storage-dir}") String storageDir,
      @Value("${workbench.visual.max-frames:200}") int maxFrames,
      @Value("${workbench.visual.scene-threshold:0.3}") double sceneThreshold,
      @Value("${workbench.visual.fallback-sampling-seconds:30}") int fallbackSamplingSeconds,
      @Value("${workbench.process-timeout-minutes:30}") long timeoutMinutes) {
    this.frames = frames;
    this.ocr = ocr;
    this.metrics = metrics;
    this.ffmpeg = ffmpeg;
    this.storage = Path.of(storageDir);
    this.maxFrames = maxFrames;
    this.sceneThreshold = sceneThreshold;
    this.fallbackSamplingSeconds = fallbackSamplingSeconds;
    this.timeoutMinutes = timeoutMinutes;
  }

  @PreDestroy
  void stop() {
    ocrExecutor.shutdownNow();
    runningExtractors.values().forEach(Process::destroyForcibly);
    runningExtractors.clear();
  }

  /** 用户取消任务时立刻终止抽帧进程；OCR 在别的线程上跑，靠 awaitOcr 的超时与降级兜底。 */
  void cancel(String taskId) {
    Process process = runningExtractors.remove(taskId);
    if (process != null) process.destroyForcibly();
  }

  /** 某任务当前是否在抽帧（供取消与诊断）。 */
  boolean extracting(String taskId) {
    return runningExtractors.containsKey(taskId);
  }

  /** 有画面文本的帧（时间升序）；无 OCR 文本时返回空列表，摘要不附加画面上下文。 */
  List<FrameText> frameTexts(String taskId) {
    return frames.frameTexts(taskId);
  }

  /** 全部帧（含 OCR 文本为空的），时间升序；供任务详情接口与帧图回源。 */
  List<FrameRepository.VideoFrame> frames(String taskId) {
    return frames.frames(taskId);
  }

  /** 删除任务时顺手清掉帧图目录；帧行由外键级联删除，文件系统这边尽力而为。 */
  void deleteFrameFiles(String taskId) {
    deleteRecursively(framesDir(taskId));
  }

  /**
   * 抽取关键帧并落库（OCR 文本此时为空）。返回帧数；失败抛 IOException，调用方决定是否降级。
   *
   * <p>重跑前先清掉旧帧行与旧帧图：语义与 {@code replaceSegments} 的全量替换一致。
   */
  int extractAndPersist(String taskId, String videoPath) throws IOException, InterruptedException {
    frames.delete(taskId);
    Path dir = framesDir(taskId);
    deleteRecursively(dir);
    Files.createDirectories(dir);
    List<VideoExtractionFrame> extracted = extractFrames(taskId, dir, Path.of(videoPath));
    List<VideoExtractionFrame> kept = dedupeAndCap(extracted, maxFrames);
    if (kept.isEmpty()) {
      log.info("未抽取到任何关键帧，任务继续（无画面分支）：taskId={}", taskId);
      return 0;
    }
    List<FrameRepository.VideoFrame> persisted = new ArrayList<>();
    for (VideoExtractionFrame frame : kept) {
      Path target = dir.resolve("frame_" + frame.tsMs() + ".jpg");
      Files.move(frame.path(), target, StandardCopyOption.REPLACE_EXISTING);
      persisted.add(
          new FrameRepository.VideoFrame(frame.tsMs(), null, frame.phash(), target.toString()));
    }
    // 去重/封顶后没被保留的候选图立刻删掉，避免目录里堆着数据库不认识的文件。
    try (var files = Files.list(dir)) {
      files
          .filter(
              path -> {
                String name = path.getFileName().toString();
                return name.startsWith("scene_") || name.startsWith("fallback_");
              })
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 清理失败不影响主流程。
                }
              });
    }
    frames.replace(taskId, persisted);
    return persisted.size();
  }

  /**
   * 在独立线程池里 OCR 本任务的全部帧并回填文本。
   *
   * @return future 完成值为 true 表示 OCR 至少部分成功；false 表示整路失败（调用方记 WARN 即可）
   */
  CompletableFuture<Boolean> ocrAsync(String taskId) {
    return CompletableFuture.supplyAsync(() -> ocrAll(taskId), ocrExecutor);
  }

  /** 等待 OCR 结束；任何异常都降级为「无画面文本」，绝不打断转写主链路。 */
  boolean awaitOcr(String taskId, CompletableFuture<Boolean> future) {
    try {
      return future.get(timeoutMinutes, TimeUnit.MINUTES);
    } catch (Exception ex) {
      log.warn("OCR 执行失败，产物按无画面文本处理：taskId={} detail={}", taskId, ex.getMessage());
      metrics.recordVisualOcrFailure();
      return false;
    }
  }

  private boolean ocrAll(String taskId) {
    List<FrameRepository.VideoFrame> all = frames.frames(taskId);
    if (all.isEmpty()) return false;
    boolean anySuccess = false;
    List<FrameRepository.VideoFrame> recognized = new ArrayList<>();
    for (FrameRepository.VideoFrame frame : all) {
      try {
        String text = ocr.ocr(Path.of(frame.path()));
        anySuccess = true;
        if (!text.isBlank())
          recognized.add(
              new FrameRepository.VideoFrame(frame.tsMs(), text, frame.phash(), frame.path()));
      } catch (Exception ex) {
        // 单帧失败不放弃整路：可能是单张图损坏；连续失败会在下方的整体降级里体现。
        log.warn("单帧 OCR 失败，跳过该帧：taskId={} ts={} detail={}", taskId, frame.tsMs(), ex.getMessage());
      }
    }
    if (!anySuccess) {
      metrics.recordVisualOcrFailure();
      log.warn("OCR 整路失败（引擎不可用或全部帧识别失败），帧保留但无画面文本：taskId={}", taskId);
      return false;
    }
    frames.updateOcrText(taskId, recognized);
    return true;
  }

  private List<VideoExtractionFrame> extractFrames(String taskId, Path dir, Path video)
      throws IOException, InterruptedException {
    // 两趟合并：场景检测抓切换点，定时 seek 采样防静态画面漏检；pHash 与时间戳去重会吃掉两趟的重叠帧。
    List<VideoExtractionFrame> extracted = new ArrayList<>();
    extracted.addAll(
        runPass(
            taskId,
            video,
            dir,
            "scene_",
            "select='eq(n,0)+gt(scene," + sceneThreshold + ")',showinfo"));
    extracted.addAll(fallbackPass(taskId, video, dir));
    return extracted;
  }

  /**
   * 保底采样：每 {@code fallbackSamplingSeconds} 秒 seek 抽一帧。
   *
   * <p>不再用 fps 滤镜做网格采样——实测在部分输入（如某些 AV1）上，fps 滤镜输出帧的内容与标注的 时间戳错位（首帧取到十几秒处的画面却标成 0），错位帧和场景通道的真第 0 帧
   * pHash 距离超阈值， 同 ts 两行一起落库就撞 {@code uk_video_frames_task_ts} 唯一键。seek 抽帧的时间戳由构造决定： 第 k 张就是第
   * k*interval 秒，不依赖滤镜的 pts 语义。
   *
   * <p>单点失败（视频流比容器时长短、seek 越界、进程被杀）只跳过该时刻，不放弃整趟； 用户取消时杀掉的进程会走同一条失败路径，残余的 seek 很快跑完，随后由任务的取消检查收尾。
   */
  private List<VideoExtractionFrame> fallbackPass(String taskId, Path video, Path dir)
      throws IOException, InterruptedException {
    double durationSeconds = probeDurationSeconds(video);
    List<VideoExtractionFrame> frames = new ArrayList<>();
    int index = 0;
    for (int second = 0; second < durationSeconds; second += fallbackSamplingSeconds) {
      Path image = dir.resolve(String.format("fallback_%04d.jpg", ++index));
      // -ss 放在 -i 前：先定位到目标时刻前的关键帧再解码到该时刻，单帧抽取最快且内容精确
      // （已在本机验证 seek 抽取与全量线性解码的帧逐字节一致）。
      List<String> command =
          List.of(
              ffmpeg,
              "-y",
              "-ss",
              String.valueOf(second),
              "-i",
              video.toString(),
              "-frames:v",
              "1",
              "-q:v",
              "3",
              image.toString());
      Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
      runningExtractors.put(taskId, process);
      boolean ok;
      try {
        ok = finishSeek(process);
      } finally {
        runningExtractors.remove(taskId, process);
      }
      if (!ok || !Files.exists(image)) {
        log.warn("保底采样单点失败，跳过：taskId={} second={}", taskId, second);
        continue;
      }
      try {
        BufferedImage decoded = ImageIO.read(image.toFile());
        if (decoded == null) {
          log.warn("帧图解码失败，跳过：taskId={} second={}", taskId, second);
          continue;
        }
        frames.add(new VideoExtractionFrame(second * 1000L, image, PerceptualHash.hash(decoded)));
      } catch (IOException ex) {
        log.warn("帧图读取失败，跳过：taskId={} second={} detail={}", taskId, second, ex.getMessage());
      }
    }
    return frames;
  }

  /** ffprobe 读容器时长（秒）；读不到就抛 IOException，让调用方按抽帧失败降级（任务继续、无画面分支）。 */
  private double probeDurationSeconds(Path video) throws IOException {
    Path ffprobe = Path.of(ffmpeg).resolveSibling("ffprobe");
    Process process;
    try {
      process =
          new ProcessBuilder(
                  ffprobe.toString(),
                  "-v",
                  "error",
                  "-show_entries",
                  "format=duration",
                  "-of",
                  "default=noprint_wrappers=1:nokey=1",
                  video.toString())
              .redirectErrorStream(false)
              .start();
    } catch (IOException ex) {
      throw new IOException("无法启动 ffprobe 读取视频时长：" + ex.getMessage(), ex);
    }
    String output;
    try {
      // 时长输出只有一行数字，先读完再等退出即可；stderr 极小，不排空也不会堵满管道缓冲区。
      output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("ffprobe 读取视频时长超时");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("ffprobe 读取视频时长被中断");
    }
    if (process.exitValue() != 0) throw new IOException("ffprobe 读取视频时长失败");
    try {
      return Double.parseDouble(output.trim());
    } catch (NumberFormatException ex) {
      throw new IOException("ffprobe 时长输出无法解析：" + tail(output));
    }
  }

  /** 单点 seek 抽帧的收尾：排空输出防管道卡死；返回进程是否在时限内成功退出。 */
  private boolean finishSeek(Process process) throws InterruptedException {
    CompletableFuture<String> stderr = drain(process.getErrorStream());
    CompletableFuture<String> stdout = drain(process.getInputStream());
    if (!process.waitFor(SEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      return false;
    }
    stderr.join();
    stdout.join();
    return process.exitValue() == 0;
  }

  /** 跑一趟 ffmpeg 抽帧并从 showinfo 输出解析每张帧的时间戳。 */
  private List<VideoExtractionFrame> runPass(
      String taskId, Path video, Path dir, String prefix, String filter)
      throws IOException, InterruptedException {
    List<String> command =
        List.of(
            ffmpeg,
            "-y",
            "-i",
            video.toString(),
            "-vf",
            filter,
            "-vsync",
            "vfr",
            "-q:v",
            "3",
            dir.resolve(prefix + "%04d.jpg").toString());
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(false);
    Process process = builder.start();
    runningExtractors.put(taskId, process);
    try {
      return finishPass(taskId, process, dir, prefix);
    } finally {
      runningExtractors.remove(taskId, process);
    }
  }

  private List<VideoExtractionFrame> finishPass(
      String taskId, Process process, Path dir, String prefix)
      throws IOException, InterruptedException {
    // showinfo 打在 stderr；先排空 stderr 再等退出，避免管道缓冲区写满把进程卡住（教训同 TaskService.run）。
    CompletableFuture<String> stderr = drain(process.getErrorStream());
    CompletableFuture<String> stdout = drain(process.getInputStream());
    if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IOException("关键帧抽取超时");
    }
    String diagnostic = stderr.join();
    stdout.join();
    if (process.exitValue() != 0) throw new IOException("关键帧抽取失败：" + tail(diagnostic));
    List<Long> timestamps = new ArrayList<>();
    Matcher matcher = PTS_TIME.matcher(diagnostic);
    while (matcher.find()) timestamps.add(Math.round(Double.parseDouble(matcher.group(1)) * 1000));
    List<VideoExtractionFrame> frames = new ArrayList<>();
    for (int index = 0; index < timestamps.size(); index++) {
      Path image = dir.resolve(String.format(prefix + "%04d.jpg", index + 1));
      if (!Files.exists(image)) continue;
      long tsMs = timestamps.get(index);
      try {
        BufferedImage decoded = ImageIO.read(image.toFile());
        if (decoded == null) continue;
        frames.add(new VideoExtractionFrame(tsMs, image, PerceptualHash.hash(decoded)));
      } catch (IOException ex) {
        log.warn("帧图读取失败，跳过：taskId={} path={} detail={}", taskId, image, ex.getMessage());
      }
    }
    return frames;
  }

  private CompletableFuture<String> drain(java.io.InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          } catch (IOException ex) {
            return "";
          }
        });
  }

  /**
   * 相邻帧去重（汉明距离 ≤ 10 视为同一画面；时间戳完全相同也视为同一时刻）+ 超上限均匀抽样。
   *
   * <p>只和「上一个保留帧」比：序列上画面是渐进变化的，与任意更早帧比对只会增加复杂度。
   *
   * <p>时间戳去重是硬约束：两趟通道（场景检测 eq(n,0) 与 fps 采样网格）都会在 0 秒处产出一帧， 而实测 fps 滤镜首帧取到的画面未必与真正的第 0 帧一致（AV1 等编码下
   * pts 归零的取帧时机不同）， 相似度比对兜不住，两行同 ts 一起落库就会撞 {@code uk_video_frames_task_ts} 唯一键。 排序是稳定的且场景通道先加入，同
   * ts 时保留的是场景通道那帧——它才是真正的第 0 帧。
   */
  static List<VideoExtractionFrame> dedupeAndCap(
      List<VideoExtractionFrame> extracted, int maxFrames) {
    List<VideoExtractionFrame> sorted =
        extracted.stream().sorted(Comparator.comparingLong(VideoExtractionFrame::tsMs)).toList();
    List<VideoExtractionFrame> kept = new ArrayList<>();
    for (VideoExtractionFrame frame : sorted) {
      if (!kept.isEmpty()) {
        VideoExtractionFrame previous = kept.get(kept.size() - 1);
        if (frame.tsMs() == previous.tsMs()) continue;
        if (PerceptualHash.distance(previous.phash(), frame.phash())
            <= PerceptualHash.DUPLICATE_DISTANCE) continue;
      }
      kept.add(frame);
    }
    if (kept.size() <= maxFrames) return kept;
    // 均匀抽样保留 maxFrames 帧：index 平铺在 [0, size-1] 区间上，首帧与末帧都保留（末帧是章节收尾的证据）。
    List<VideoExtractionFrame> capped = new ArrayList<>(maxFrames);
    for (int index = 0; index < maxFrames; index++)
      capped.add(kept.get(index * (kept.size() - 1) / (maxFrames - 1)));
    return capped;
  }

  private Path framesDir(String taskId) {
    return storage.resolve(taskId).resolve("frames");
  }

  private void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) return;
    try (var paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 清理失败不影响主流程。
                }
              });
    } catch (IOException ignored) {
      // 同上。
    }
  }

  private static String tail(String output) {
    String compact = output == null ? "" : output.replaceAll("\\s+", " ").trim();
    return compact.length() <= 240 ? compact : compact.substring(compact.length() - 240);
  }

  /** 抽取阶段的候选帧：还没确定要不要保留，路径指向趟次前缀的临时文件名。 */
  record VideoExtractionFrame(long tsMs, Path path, long phash) {}
}
