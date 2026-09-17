package ai.toni.videoworkbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class CoderplanClient {
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final String reasoningEffort;

  CoderplanClient(
      ObjectMapper json,
      @Value("${workbench.coderplan.base-url}") String baseUrl,
      @Value("${workbench.coderplan.api-key}") String apiKey,
      @Value("${workbench.coderplan.model}") String model,
      @Value("${workbench.coderplan.reasoning-effort}") String reasoningEffort) {
    this.json = json;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.apiKey = apiKey;
    this.model = model;
    this.reasoningEffort = reasoningEffort;
  }

  TaskResult summarize(List<TranscriptSegment> transcript) throws Exception {
    String source =
        transcript.stream()
            .map(
                s ->
                    "[id="
                        + s.id()
                        + ", startMs="
                        + s.startMs()
                        + ", endMs="
                        + s.endMs()
                        + "] "
                        + s.text())
            .reduce("", (a, b) -> a + "\n" + b);
    String prompt =
        "根据以下带时间戳中文转写生成 JSON。格式严格为 {summary:string,keyPoints:string[],chapters:[{startMs:number,endMs:number,title:string,sourceSegmentId:number}]}。chapter 必须完全位于 sourceSegmentId 对应片段范围内，按时间排序。不要使用 Markdown。\n"
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
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new IllegalStateException("内容服务返回 HTTP " + response.statusCode());
    JsonNode root = json.readTree(response.body());
    String content = root.path("choices").path(0).path("message").path("content").asText();
    if (content.isBlank()) throw new IllegalStateException("内容服务未返回结果");
    return json.readValue(content, TaskResult.class);
  }

  List<TranscriptSegment> translateToChinese(List<TranscriptSegment> transcript) throws Exception {
    String source =
        transcript.stream()
            .map(s -> "[id=" + s.id() + "] " + s.text())
            .reduce("", (a, b) -> a + "\n" + b);
    String prompt =
        "将以下非中文视频逐字稿逐条翻译为简体中文。格式严格为 {translations:[{id:number,translation:string}]}。保留每个 id，逐条对应，不要省略、合并或添加 Markdown。\n"
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
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new IllegalStateException("翻译服务返回 HTTP " + response.statusCode());
    String content =
        json.readTree(response.body())
            .path("choices")
            .path(0)
            .path("message")
            .path("content")
            .asText();
    Map<Long, String> translations = new HashMap<>();
    for (JsonNode item : json.readTree(content).path("translations"))
      translations.put(item.path("id").asLong(), item.path("translation").asText().trim());
    if (translations.size() != transcript.size()
        || translations.values().stream().anyMatch(String::isBlank))
      throw new IllegalStateException("翻译服务未返回完整结果");
    return transcript.stream()
        .map(
            s ->
                new TranscriptSegment(
                    s.id(), s.startMs(), s.endMs(), s.text(), translations.get(s.id())))
        .toList();
  }

  boolean configured() {
    return !apiKey.isBlank();
  }
}
