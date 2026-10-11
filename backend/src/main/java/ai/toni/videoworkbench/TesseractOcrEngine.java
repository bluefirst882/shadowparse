package ai.toni.videoworkbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tesseract 命令行 OCR：{@code tesseract <image> stdout -l chi_sim+eng}。
 *
 * <p>与 FFmpeg 一样走外部进程：识别挂在独立的 CPU 有界线程池上（见 {@link VisualExtractionService}）， 不占 Whisper 的 GPU。语言包缺
 * chi_sim 会在首次调用时暴露为失败，由调用方降级为「无画面文本」。
 */
@Component
class TesseractOcrEngine implements OcrEngine {
  private final String command;
  private final String languages;
  private final Duration timeout;

  TesseractOcrEngine(
      @Value("${workbench.ocr.command:tesseract}") String command,
      @Value("${workbench.ocr.languages:chi_sim+eng}") String languages,
      @Value("${workbench.ocr.timeout-seconds:30}") int timeoutSeconds) {
    this.command = command;
    this.languages = languages;
    this.timeout = Duration.ofSeconds(timeoutSeconds);
  }

  @Override
  public String ocr(Path image) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(List.of(command, image.toString(), "stdout", "-l", languages));
    builder.environment().put("PYTHONIOENCODING", "utf-8");
    Process process = builder.start();
    String stdout;
    try {
      boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new IOException("OCR 进程超时（" + timeout.toSeconds() + "s）");
      }
      stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        String diagnostic =
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        throw new IOException("OCR 进程失败：" + tail(diagnostic));
      }
    } finally {
      process.destroyForcibly();
    }
    return stdout.trim();
  }

  private static String tail(String output) {
    String compact = output == null ? "" : output.replaceAll("\\s+", " ").trim();
    return compact.length() <= 240 ? compact : compact.substring(compact.length() - 240);
  }
}
