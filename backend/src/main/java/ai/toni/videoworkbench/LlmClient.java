package ai.toni.videoworkbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class LlmClient {
  private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

  /**
   * 要点数超过该值时告警。阈值继承自旧的 {@code keyPoints.distinct().limit(12)} 静默截断上限—— 现在它只用于日志告警，绝不再丢弃要点，超出部分照常返回。
   */
  private static final int KEY_POINT_WARN_THRESHOLD = 12;

  /** 要点归一化时去掉尾部空白与标点，用于合并仅标点/空白不同的重复要点。 */
  private static final Pattern TRAILING_KEY_POINT_PUNCTUATION =
      Pattern.compile("[\\s\\p{Z}\\p{P}]+$");

  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final String fallbackModel;
  private final String reasoningEffort;
  private final WorkbenchMetrics metrics;

  LlmClient(
      ObjectMapper json,
      WorkbenchMetrics metrics,
      @Value("${workbench.llm.base-url}") String baseUrl,
      @Value("${workbench.llm.api-key}") String apiKey,
      @Value("${workbench.llm.model}") String model,
      @Value("${workbench.llm.fallback-model:}") String fallbackModel,
      @Value("${workbench.llm.reasoning-effort}") String reasoningEffort) {
    this.json = json;
    this.metrics = metrics;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.apiKey = apiKey;
    this.model = model;
    this.fallbackModel = fallbackModel == null ? "" : fallbackModel.strip();
    this.reasoningEffort = reasoningEffort;
  }

  /**
   * 生成摘要、要点与章节。
   *
   * <p>{@code previousError} 为上一次尝试未通过校验的失败原因（首次调用为 {@code null}）。重试时把失败原因
   * 回灌提示词：同一个提示词重试大概率仍产出同样的幻觉，只有把「上次错在哪」告诉模型，重试才有意义。
   */
  TaskResult summarize(List<TranscriptSegment> transcript, int maxInputChars, String previousError)
      throws Exception {
    List<List<TranscriptSegment>> chunks = partitionTranscript(transcript, maxInputChars);
    List<TaskResult> partialResults = new ArrayList<>();
    for (List<TranscriptSegment> chunk : chunks)
      partialResults.add(summarizeChunk(chunk, previousError));
    // chapters 由各块结果确定性合并排序，不交给模型重写：模型重写可能改动 sourceSegmentId 或时间，
    // 破坏服务端的时间轴与引文校验。只有 summary / keyPoints 这类纯文本才适合二次合并。
    List<Chapter> chapters =
        partialResults.stream()
            .flatMap(result -> result.chapters().stream())
            .sorted(Comparator.comparingLong(Chapter::startMs))
            .toList();
    // 分块数 > 1 时才额外发起一次合并去重调用；单块直接沿用其结果，避免无意义的额外请求。
    String summary;
    List<String> rawKeyPoints;
    if (chunks.size() > 1) {
      MergedResult merged = mergeChunks(partialResults, previousError);
      summary = merged.summary();
      rawKeyPoints = merged.keyPoints();
    } else {
      summary = partialResults.getFirst().summary();
      rawKeyPoints = partialResults.getFirst().keyPoints();
    }
    List<String> keyPoints = dedupeKeyPoints(rawKeyPoints);
    if (keyPoints.size() > KEY_POINT_WARN_THRESHOLD)
      log.warn("要点数 {} 超过告警阈值 {}，已全部保留（旧实现会静默截断）", keyPoints.size(), KEY_POINT_WARN_THRESHOLD);
    return new TaskResult(summary, keyPoints, chapters);
  }

  /** 分块结果的纯文本二次合并产物；chapters 不在此列，见 {@link #summarize}。 */
  private record MergedResult(String summary, List<String> keyPoints) {}

  /**
   * 合并各块的 summary 与 keyPoints：要求模型输出合并去重后的 {@code {summary,keyPoints[]}}，只做纯文本整合。
   * 提示词只要求这两个字段，模型不接触章节引用与时间。
   */
  private MergedResult mergeChunks(List<TaskResult> partialResults, String previousError)
      throws Exception {
    StringBuilder source = new StringBuilder();
    for (int index = 0; index < partialResults.size(); index++) {
      TaskResult partial = partialResults.get(index);
      source
          .append("第 ")
          .append(index + 1)
          .append(" 块摘要：")
          .append(partial.summary())
          .append('\n')
          .append("第 ")
          .append(index + 1)
          .append(" 块要点：\n");
      for (String point : partial.keyPoints()) source.append("- ").append(point).append('\n');
      source.append('\n');
    }
    String prompt =
        PromptLibrary.MERGE.user(
            Map.of(
                "schema", LlmJsonSchema.MERGE.json(),
                "retryHint", retryHint(previousError),
                "input", source.toString()));
    JsonNode merged =
        requestJson(
            "summarize", PromptLibrary.MERGE, prompt, LlmJsonSchema.MERGE, Duration.ofSeconds(90));
    List<String> keyPoints = new ArrayList<>();
    for (JsonNode point : merged.path("keyPoints")) keyPoints.add(point.asText());
    return new MergedResult(merged.path("summary").asText(), keyPoints);
  }

  /**
   * 按归一化后的键去重，合并仅标点/空白不同的重复要点；保留首次出现的原文（去首尾空白），不改变顺序。 相比 {@code distinct()}
   * 的精确比较，这里能吃掉模型为同一要点补上的尾部标点或空白差异。
   */
  static List<String> dedupeKeyPoints(List<String> keyPoints) {
    Map<String, String> unique = new LinkedHashMap<>();
    for (String point : keyPoints) {
      String value = point.strip();
      unique.putIfAbsent(TRAILING_KEY_POINT_PUNCTUATION.matcher(value).replaceAll(""), value);
    }
    return List.copyOf(unique.values());
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

  private TaskResult summarizeChunk(List<TranscriptSegment> transcript, String previousError)
      throws Exception {
    String source =
        transcript.stream().map(LlmClient::sourceLine).collect(Collectors.joining("\n"));
    String prompt =
        PromptLibrary.SUMMARIZE.user(
            Map.of(
                "schema",
                LlmJsonSchema.SUMMARIZE.json(),
                "retryHint",
                retryHint(previousError),
                "input",
                source));
    JsonNode content =
        requestJson(
            "summarize",
            PromptLibrary.SUMMARIZE,
            prompt,
            LlmJsonSchema.SUMMARIZE,
            Duration.ofSeconds(90));
    return json.treeToValue(content, TaskResult.class);
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

  /** 把上一次的校验失败原因作为提示词尾部的独立一行；首次调用（无失败）返回空串。 */
  private static String retryHint(String previousError) {
    return previousError == null || previousError.isBlank()
        ? ""
        : "上次输出未通过校验：" + previousError + "，请修正后重新返回完整 JSON。\n";
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
          PromptLibrary.TRANSLATE.user(
              Map.of("schema", LlmJsonSchema.TRANSLATE.json(), "input", source));
      JsonNode result =
          requestJson(
              "translate",
              PromptLibrary.TRANSLATE,
              prompt,
              LlmJsonSchema.TRANSLATE,
              Duration.ofSeconds(180));
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

  /**
   * 发起一次结构化请求：约束返回体为 JSON 对象，解析后按 schema 机械校验；任一步失败即沿模型降级链换模型重试。
   *
   * <p>降级链是「主模型 → 备用模型」的一次性升级：flash 超时、报错或输出结构不可用时升级到 pro， 并打点、记 WARN 日志，便于确认降级路径真的被走到。
   */
  private JsonNode requestJson(
      String operation,
      PromptLibrary.Prompt prompt,
      String userPrompt,
      LlmJsonSchema.Schema schema,
      Duration timeout)
      throws Exception {
    List<String> models = modelChain();
    Exception failure = null;
    for (int index = 0; index < models.size(); index++) {
      String current = models.get(index);
      try {
        return callOnce(current, operation, prompt, userPrompt, schema, timeout);
      } catch (Exception ex) {
        failure = ex;
        if (index + 1 < models.size()) {
          String next = models.get(index + 1);
          metrics.recordLlmFallback(operation, reasonOf(ex));
          log.warn("模型 {} 调用失败（{}）：{}；降级到 {}", current, reasonOf(ex), tail(ex.getMessage()), next);
        }
      }
    }
    throw failure;
  }

  /** 主模型 + 可选备用模型；未配置备用模型或与主模型同名时只调用主模型。 */
  private List<String> modelChain() {
    if (fallbackModel.isBlank() || fallbackModel.equals(model)) return List.of(model);
    return List.of(model, fallbackModel);
  }

  private JsonNode callOnce(
      String currentModel,
      String operation,
      PromptLibrary.Prompt prompt,
      String userPrompt,
      LlmJsonSchema.Schema schema,
      Duration timeout)
      throws Exception {
    // DeepSeek 官方 API 目前不支持 response_format=json_schema（实测返回 400），
    // 所以用 json_object 保证「一定是合法 JSON」，字段级结构由提示词内的 schema + 下方本地校验承担。
    Map<String, Object> body =
        Map.of(
            "model",
            currentModel,
            "reasoning_effort",
            reasoningEffort,
            "messages",
            List.of(
                Map.of("role", "system", "content", prompt.system()),
                Map.of("role", "user", "content", userPrompt)),
            "response_format",
            Map.of("type", "json_object"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    JsonNode response = send(request, "内容服务", operation, currentModel);
    String content = response.path("choices").path(0).path("message").path("content").asText();
    if (content.isBlank()) throw outputFailure(operation, "empty_content", "内容服务未返回结果");
    JsonNode parsed;
    try {
      parsed = json.readTree(extractJson(content));
    } catch (Exception ex) {
      throw outputFailure(operation, "invalid_json", "内容服务未返回有效 JSON");
    }
    List<String> violations = LlmJsonSchema.validate(parsed, schema.node());
    if (!violations.isEmpty())
      throw outputFailure(
          operation, "schema_violation", "返回结构不符合 JSON Schema：" + String.join("；", violations));
    return parsed;
  }

  /** 记录一次结构化输出失败（空内容 / 非法 JSON / 结构不符），并按原因打点。 */
  private LlmOutputException outputFailure(String operation, String reason, String message) {
    metrics.recordLlmOutputFailure(operation, reason);
    return new LlmOutputException(reason, message);
  }

  /** 把异常归入稳定的降级原因分类，避免把 HTTP 状态码、异常类名写进标签形成高基数。 */
  static String reasonOf(Exception ex) {
    if (ex instanceof LlmOutputException output) return output.reason();
    if (ex instanceof LlmApiException api) return "http_" + api.status();
    if (ex instanceof HttpTimeoutException) return "timeout";
    if (ex instanceof IOException) return "io_error";
    return "unknown";
  }

  private static String tail(String message) {
    if (message == null) return "";
    String compact = message.replaceAll("\\s+", " ").trim();
    return compact.length() <= 200 ? compact : compact.substring(0, 200);
  }

  private JsonNode send(HttpRequest request, String service, String operation, String currentModel)
      throws Exception {
    boolean success = false;
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new LlmApiException(
            response.statusCode(),
            service + "返回 HTTP " + response.statusCode() + "：" + shortBody(response.body()));
      JsonNode body = json.readTree(response.body());
      recordUsage(currentModel, body);
      success = true;
      return body;
    } finally {
      metrics.recordLlmCall(operation, success);
    }
  }

  /** 从响应 usage 字段累计 prompt / completion token 用量，按实际服务的模型归属。 */
  private void recordUsage(String currentModel, JsonNode response) {
    JsonNode usage = response.path("usage");
    if (!usage.isObject()) return;
    metrics.recordLlmTokens(
        currentModel,
        usage.path("prompt_tokens").asLong(),
        usage.path("completion_tokens").asLong());
  }

  /** LLM 服务返回非 2xx；{@code status} 用于降级原因分类。 */
  static class LlmApiException extends IllegalStateException {
    private final int status;

    LlmApiException(int status, String message) {
      super(message);
      this.status = status;
    }

    int status() {
      return status;
    }
  }

  /** 模型输出不可用：空内容、非法 JSON 或不符合 schema；{@code reason} 用于指标与降级分类。 */
  static class LlmOutputException extends IllegalStateException {
    private final String reason;

    LlmOutputException(String reason, String message) {
      super(message);
      this.reason = reason;
    }

    String reason() {
      return reason;
    }
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
