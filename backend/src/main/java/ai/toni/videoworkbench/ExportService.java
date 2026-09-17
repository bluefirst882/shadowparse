package ai.toni.videoworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ExportService {
  private final TaskService tasks;
  private final ObjectMapper json;

  ExportService(TaskService tasks, ObjectMapper json) {
    this.tasks = tasks;
    this.json = json;
  }

  String export(String id, String format) {
    TaskDetails details = tasks.details(id);
    return switch (format.toLowerCase(Locale.ROOT)) {
      case "srt" -> srt(details);
      case "md" -> markdown(details);
      case "json" -> asJson(details);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的导出格式");
    };
  }

  private String srt(TaskDetails data) {
    StringBuilder out = new StringBuilder();
    int index = 1;
    for (TranscriptSegment s : data.transcript())
      out.append(index++)
          .append('\n')
          .append(stamp(s.startMs()))
          .append(" --> ")
          .append(stamp(s.endMs()))
          .append('\n')
          .append(s.text())
          .append("\n\n");
    return out.toString();
  }

  private String markdown(TaskDetails data) {
    StringBuilder out = new StringBuilder("# ").append(data.task().fileName()).append("\n\n");
    if (data.result() != null) {
      out.append("## 摘要\n\n").append(data.result().summary()).append("\n\n## 要点\n\n");
      for (String point : data.result().keyPoints()) out.append("- ").append(point).append('\n');
    }
    out.append("\n## 转写\n\n");
    for (TranscriptSegment s : data.transcript())
      out.append("**").append(stamp(s.startMs())).append("** ").append(s.text()).append("\n\n");
    return out.toString();
  }

  private String asJson(TaskDetails data) {
    try {
      return json.writerWithDefaultPrettyPrinter().writeValueAsString(data);
    } catch (Exception ex) {
      throw new IllegalStateException("无法导出 JSON", ex);
    }
  }

  private String stamp(long millis) {
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d,%03d",
        millis / 3600000,
        millis / 60000 % 60,
        millis / 1000 % 60,
        millis % 1000);
  }
}
