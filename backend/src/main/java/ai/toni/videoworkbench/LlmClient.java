package ai.toni.videoworkbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class LlmClient {
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final String reasoningEffort;
  private final WorkbenchMetrics metrics;

  LlmClient(
      ObjectMapper json,
      WorkbenchMetrics metrics,
      @Value("${workbench.llm.base-url}") String baseUrl,
      @Value("${workbench.llm.api-key}") String apiKey,
      @Value("${workbench.llm.model}") String model,
      @Value("${workbench.llm.reasoning-effort}") String reasoningEffort) {
    this.json = json;
    this.metrics = metrics;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.apiKey = apiKey;
    this.model = model;
    this.reasoningEffort = reasoningEffort;
  }

  TaskResult summarize(List<TranscriptSegment> transcript, int maxInputChars) throws Exception {
    List<TaskResult> partialResults = new ArrayList<>();
    for (List<TranscriptSegment> chunk : partitionTranscript(transcript, maxInputChars))
      partialResults.add(summarizeChunk(chunk));
    return new TaskResult(
        partialResults.stream().map(TaskResult::summary).collect(Collectors.joining("\n\n")),
        partialResults.stream()
            .flatMap(result -> result.keyPoints().stream())
            .distinct()
            .limit(12)
            .toList(),
        partialResults.stream()
            .flatMap(result -> result.chapters().stream())
            .sorted(Comparator.comparingLong(Chapter::startMs))
            .toList());
  }

  static List<List<TranscriptSegment>> partitionTranscript(
      List<TranscriptSegment> transcript, int maxInputChars) {
    if (maxInputChars <= 0) throw new IllegalArgumentException("摘要输入长度上限必须为正数");
    List<List<TranscriptSegment>> chunks = new ArrayList<>();
    List<TranscriptSegment> current = new ArrayList<>();
    int currentChars = 0;
    for (TranscriptSegment segment : transcript) {
      int segmentChars = sourceLine(segment).length() + 1;
      if (segmentChars > maxInputChars) throw new IllegalArgumentException("单个转写片段超过摘要输入长度上限");
      if (!current.isEmpty() && currentChars + segmentChars > maxInputChars) {
        chunks.add(List.copyOf(current));
        current.clear();
        currentChars = 0;
      }
      current.add(segment);
      currentChars += segmentChars;
    }
    if (!current.isEmpty()) chunks.add(List.copyOf(current));
    return chunks;
  }

  private TaskResult summarizeChunk(List<TranscriptSegment> transcript) throws Exception {
    String source =
        transcript.stream().map(LlmClient::sourceLine).collect(Collectors.joining("\n"));
    String prompt =
        "根据以下带时间戳中文转写生成 JSON。格式严格为 {summary:string,keyPoints:string[],chapters:[{startMs:number,endMs:number,title:string,sourceSegmentId:number,sourceEndSegmentId:number}]}。chapter 可覆盖连续片段：sourceSegmentId 引用开始片段，sourceEndSegmentId 引用结束片段；单片段章节两个 id 相同。章节时间必须位于首尾引用片段的范围内，按时间排序。不要使用 Markdown。\n"
            + source;
    Map<String, Object> body =
        Map.of(
            "model",
            model,
            "reasoning_effort",
            reasoningEffort,
            "messages",
            List.of(
                Map.of("role", "system", "content", "你是视频内容分析助手，只返回合法 JSON。"),
                Map.of("role", "user", "content", prompt)),
            "response_format",
            Map.of("type", "json_object"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    JsonNode response = send(request, "内容服务", "summarize");
    String content = response.path("choices").path(0).path("message").path("content").asText();
    if (content.isBlank()) throw new IllegalStateException("内容服务未返回结果");
    TaskResult result = json.readValue(extractJson(content), TaskResult.class);
    if (result.summary() == null || result.keyPoints() == null || result.chapters() == null)
      throw new IllegalStateException("内容服务未返回完整的摘要结构");
    return result;
  }

  private static String sourceLine(TranscriptSegment segment) {
    return "[id="
        + segment.id()
        + ", startMs="
        + segment.startMs()
        + ", endMs="
        + segment.endMs()
        + "] "
        + segment.text();
  }

  List<TranscriptSegment> translateToChinese(List<TranscriptSegment> transcript) throws Exception {
    Map<Long, String> translations = new HashMap<>();
    // Translation output is much longer than the input. Small batches prevent the model
    // from silently omitting trailing segments when a whole transcript is sent at once.
    for (List<TranscriptSegment> chunk : partitionByCount(transcript, 8)) {
      String source =
          chunk.stream()
              .map(s -> "[id=" + s.id() + "] " + s.text())
              .collect(Collectors.joining("\n"));
      String prompt =
          "将以下非中文视频逐字稿逐条翻译为简体中文。格式严格为 {translations:[{id:number,translation:string}]}。必须返回本批全部 id，保留 id，逐条对应，不要省略、合并或添加 Markdown。\n"
              + source;
      Map<String, Object> body =
          Map.of(
              "model",
              model,
              "reasoning_effort",
              reasoningEffort,
              "messages",
              List.of(
                  Map.of("role", "system", "content", "你是专业字幕翻译助手，只返回合法 JSON。"),
                  Map.of("role", "user", "content", prompt)),
              "response_format",
              Map.of("type", "json_object"));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
              .timeout(Duration.ofSeconds(180))
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      JsonNode response = send(request, "翻译服务", "translate");
      String content = response.path("choices").path(0).path("message").path("content").asText();
      JsonNode result = json.readTree(extractJson(content));
      for (JsonNode item : result.path("translations"))
        translations.put(item.path("id").asLong(), item.path("translation").asText().trim());
      if (chunk.stream()
          .anyMatch(s -> !translations.containsKey(s.id()) || translations.get(s.id()).isBlank()))
        throw new IllegalStateException("翻译服务未返回完整结果（本批应返回 " + chunk.size() + " 条）");
    }
    if (transcript.stream().anyMatch(s -> !translations.containsKey(s.id())))
      throw new IllegalStateException("翻译服务未返回完整结果");
    return transcript.stream()
        .map(
            s ->
                new TranscriptSegment(
                    s.id(), s.startMs(), s.endMs(), s.text(), translations.get(s.id())))
        .toList();
  }

  private JsonNode send(HttpRequest request, String service, String operation) throws Exception {
    boolean success = false;
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new IllegalStateException(
            service + "返回 HTTP " + response.statusCode() + "：" + shortBody(response.body()));
      JsonNode body = json.readTree(response.body());
      recordUsage(body);
      success = true;
      return body;
    } finally {
      metrics.recordLlmCall(operation, success);
    }
  }

  /** 从响应 usage 字段累计 prompt / completion token 用量（未持久化，仅作为运行指标）。 */
  private void recordUsage(JsonNode response) {
    JsonNode usage = response.path("usage");
    if (!usage.isObject()) return;
    metrics.recordLlmTokens(
        model, usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
  }

  private static String shortBody(String body) {
    if (body == null || body.isBlank()) return "无响应内容";
    String value = body.replaceAll("\\s+", " ").trim();
    return value.length() > 300 ? value.substring(0, 300) : value;
  }

  private static List<List<TranscriptSegment>> partitionByCount(
      List<TranscriptSegment> transcript, int batchSize) {
    List<List<TranscriptSegment>> batches = new ArrayList<>();
    for (int start = 0; start < transcript.size(); start += batchSize)
      batches.add(
          List.copyOf(transcript.subList(start, Math.min(start + batchSize, transcript.size()))));
    return batches;
  }

  private static String extractJson(String content) {
    String value = content == null ? "" : content.trim();
    if (value.startsWith("```") && value.endsWith("```")) {
      int firstLine = value.indexOf('\n');
      value = firstLine >= 0 ? value.substring(firstLine + 1, value.length() - 3).trim() : value;
    }
    int start = value.indexOf('{');
    int end = value.lastIndexOf('}');
    if (start < 0 || end < start) throw new IllegalStateException("内容服务未返回有效 JSON");
    return value.substring(start, end + 1);
  }

  boolean configured() {
    return !apiKey.isBlank();
  }
}
