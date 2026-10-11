package ai.toni.videoworkbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
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
  private final LlmUsageRepository usageLog;
  private final DownstreamResilience resilience;
  private final LlmBudgetGuard budget;
  private final Duration summarizeTimeout;
  private final Duration translateTimeout;

  LlmClient(
      ObjectMapper json,
      WorkbenchMetrics metrics,
      LlmUsageRepository usageLog,
      DownstreamResilience resilience,
      LlmBudgetGuard budget,
      @Value("${workbench.llm.base-url}") String baseUrl,
      @Value("${workbench.llm.api-key}") String apiKey,
      @Value("${workbench.llm.model}") String model,
      @Value("${workbench.llm.fallback-model:}") String fallbackModel,
      @Value("${workbench.llm.reasoning-effort}") String reasoningEffort,
      @Value("${workbench.llm.timeout-seconds:90}") int summarizeTimeoutSeconds,
      @Value("${workbench.llm.translate-timeout-seconds:180}") int translateTimeoutSeconds) {
    this.json = json;
    this.metrics = metrics;
    this.usageLog = usageLog;
    this.resilience = resilience;
    this.budget = budget;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.apiKey = apiKey;
    this.model = model;
    this.fallbackModel = fallbackModel == null ? "" : fallbackModel.strip();
    this.reasoningEffort = reasoningEffort;
    this.summarizeTimeout = Duration.ofSeconds(summarizeTimeoutSeconds);
    this.translateTimeout = Duration.ofSeconds(translateTimeoutSeconds);
  }

  /**
   * 生成摘要、要点与章节（无画面文本的旧入口）。
   *
   * <p>{@code previousError} 为上一次尝试未通过校验的失败原因（首次调用为 {@code null}）。重试时把失败原因
   * 回灌提示词：同一个提示词重试大概率仍产出同样的幻觉，只有把「上次错在哪」告诉模型，重试才有意义。
   *
   * <p>{@code taskId} 只用于把这次调用的 token 用量记到对应视频名下。
   */
  TaskResult summarize(
      String taskId, List<TranscriptSegment> transcript, int maxInputChars, String previousError)
      throws Exception {
    return summarize(taskId, transcript, List.of(), maxInputChars, previousError);
  }

  /**
   * 带画面文本的摘要：OCR 文本按时间归并进分块，只作上下文参考。
   *
   * <p>画面文字从不进入校验链路——章节的 {@code sourceSegmentId} 与 {@code quote} 闸门仍然只认转写片段， OCR 有识别错误，不能当引文来源（见
   * docs/改造方案与优先级.md P1）。
   */
  TaskResult summarize(
      String taskId,
      List<TranscriptSegment> transcript,
      List<FrameText> frameTexts,
      int maxInputChars,
      String previousError)
      throws Exception {
    List<List<TranscriptSegment>> chunks = partitionTranscript(transcript, maxInputChars);
    List<String> visualBlocks = attachFrameTexts(chunks, frameTexts);
    List<TaskResult> partialResults = new ArrayList<>();
    for (int index = 0; index < chunks.size(); index++)
      partialResults.add(
          summarizeChunk(taskId, chunks.get(index), visualBlocks.get(index), previousError));
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
      MergedResult merged = mergeChunks(taskId, partialResults, previousError);
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
  private MergedResult mergeChunks(
      String taskId, List<TaskResult> partialResults, String previousError) throws Exception {
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
        requestJson(taskId, "summarize", PromptLibrary.MERGE, prompt, LlmJsonSchema.MERGE);
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

  /**
   * 把画面文本按时间归并进分块：落在分块时间范围内的帧文本归属该分块，首尾之外的帧归最近的分块。
   *
   * <p>返回与 {@code chunks} 等长的列表，每项是拼好的「画面文字」段（无帧文本时为空串）， 直接填进提示词的 {@code {{visualText}}} 占位符。
   */
  static List<String> attachFrameTexts(
      List<List<TranscriptSegment>> chunks, List<FrameText> frameTexts) {
    List<String> blocks = new ArrayList<>(chunks.size());
    if (chunks.isEmpty()) return blocks;
    List<List<String>> grouped = new ArrayList<>(chunks.size());
    for (int index = 0; index < chunks.size(); index++) grouped.add(new ArrayList<>());
    for (FrameText frame : frameTexts) {
      if (frame.text() == null || frame.text().isBlank()) continue;
      int target = -1;
      for (int index = 0; index < chunks.size(); index++) {
        List<TranscriptSegment> chunk = chunks.get(index);
        long start = chunk.getFirst().startMs();
        long end = chunk.get(chunk.size() - 1).endMs();
        if (frame.tsMs() >= start && frame.tsMs() <= end) {
          target = index;
          break;
        }
        if (frame.tsMs() < start && target == -1) target = index;
      }
      if (target == -1) target = chunks.size() - 1;
      grouped.get(target).add("[" + timestamp(frame.tsMs()) + "] " + frame.text().strip());
    }
    for (List<String> group : grouped) {
      if (group.isEmpty()) {
        blocks.add("");
        continue;
      }
      blocks.add("画面文字（OCR 识别，可能有误，仅作上下文）：\n" + String.join("\n", group) + "\n");
    }
    return blocks;
  }

  /** 毫秒时间戳的 mm:ss（超一小时含小时段），与导出文稿的展示习惯一致。 */
  static String timestamp(long tsMs) {
    long totalSeconds = Math.max(0, tsMs) / 1000;
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    return hours > 0
        ? String.format("%d:%02d:%02d", hours, minutes, seconds)
        : String.format("%02d:%02d", minutes, seconds);
  }

  private TaskResult summarizeChunk(
      String taskId, List<TranscriptSegment> transcript, String visualText, String previousError)
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
                "visualText",
                visualText == null ? "" : visualText,
                "input",
                source));
    JsonNode content =
        requestJson(taskId, "summarize", PromptLibrary.SUMMARIZE, prompt, LlmJsonSchema.SUMMARIZE);
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

  /** 单批翻译的重试次数：LLM 偶发漏条是输出质量问题，重试同一批通常就能拿到完整结果。 */
  private static final int TRANSLATE_BATCH_ATTEMPTS = 3;

  List<TranscriptSegment> translateToChinese(String taskId, List<TranscriptSegment> transcript)
      throws Exception {
    Map<Long, String> translations = new HashMap<>();
    // Translation output is much longer than the input. Small batches prevent the model
    // from silently omitting trailing segments when a whole transcript is sent at once.
    for (List<TranscriptSegment> chunk : partitionByCount(transcript, 8)) {
      String source =
          chunk.stream()
              .map(s -> "[id=" + s.id() + "] " + s.text())
              .collect(Collectors.joining("\n"));
      // 单批不完整只重试该批（模型漏条是偶发的），重试把整批八条都要一遍，补齐后再进下一批；
      // 次数用完仍缺条才放弃——此前 27 批的翻译成果都在 translations 里，不因一批而前功尽弃。
      IllegalStateException incomplete = null;
      for (int attempt = 0; attempt < TRANSLATE_BATCH_ATTEMPTS; attempt++) {
        String prompt =
            PromptLibrary.TRANSLATE.user(
                Map.of("schema", LlmJsonSchema.TRANSLATE.json(), "input", source));
        JsonNode result =
            requestJson(
                taskId, "translate", PromptLibrary.TRANSLATE, prompt, LlmJsonSchema.TRANSLATE);
        for (JsonNode item : result.path("translations"))
          translations.put(item.path("id").asLong(), item.path("translation").asText().trim());
        boolean complete =
            chunk.stream()
                .allMatch(
                    s -> translations.containsKey(s.id()) && !translations.get(s.id()).isBlank());
        if (complete) {
          incomplete = null;
          break;
        }
        incomplete = new IllegalStateException("翻译服务未返回完整结果（本批应返回 " + chunk.size() + " 条）");
      }
      if (incomplete != null) throw incomplete;
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
   * 发起一次结构化请求：先做预算检查，再过容错外壳（速率限制 + 并发隔板 + 熔断器），最后走模型降级链。
   *
   * <p>预算检查刻意放在容错外壳<b>之前</b>：预算超限不是下游故障，放进熔断器的计数里会把 「钱花完了」误判成「服务坏了」而打开熔断器，让后续无关调用也被拦。
   *
   * <p>预算超限的处理是降级续跑（见 docs/改造方案与优先级.md P0 决策 #3）：把调用切到廉价档模型， 质量有损但任务当日可用；只有廉价档不可用时才抛 {@link
   * BudgetExhaustedException}（非瞬时故障， 任务 FAILED 而不是无限重试）。降级的调用在账目里带 {@code budget_fallback}
   * 标记，可事后统计。
   *
   * <p>熔断打开、隔板已满或速率超限时请求根本没发出去，归为瞬时故障交给队列退避重试；这类拒绝**不走**降级链， 因为备用模型同样会被同一组本地护栏挡住，重试只是把 3 次失败拖成 6
   * 次。超时取本操作配置的 {@code timeout} （见 {@code workbench.llm.timeout-seconds} / {@code
   * translate-timeout-seconds}），由 HTTP 客户端强制。
   *
   * <p>降级链的判定在 {@link #requestJsonUnprotected}：主模型超时、报错或输出结构不可用时升级到备用模型， 并打点、记 WARN
   * 日志，便于确认降级路径真的被走到。
   */
  private JsonNode requestJson(
      String taskId,
      String operation,
      PromptLibrary.Prompt prompt,
      String userPrompt,
      LlmJsonSchema.Schema schema)
      throws Exception {
    Duration timeout = PromptLibrary.TRANSLATE == prompt ? translateTimeout : summarizeTimeout;
    List<String> models = modelChain();
    boolean budgetFallback = false;
    if (budget.overBudget(taskId)) {
      String cheap = budget.cheapModel();
      if (cheap.isBlank() || cheap.equals(models.get(0)))
        throw new BudgetExhaustedException(
            "当日 AI 预算已用完，且未配置更廉价的降级模型（workbench.llm.budget-fallback-model）");
      models = List.of(cheap);
      budgetFallback = true;
      metrics.recordLlmBudgetFallback();
      log.warn("当日 LLM 预算已超限，本次调用降级到廉价档模型 {}：taskId={} operation={}", cheap, taskId, operation);
    }
    List<String> finalModels = models;
    boolean finalBudgetFallback = budgetFallback;
    try {
      return resilience.call(
          DownstreamResilience.Target.LLM,
          () ->
              requestJsonUnprotected(
                  taskId,
                  operation,
                  prompt,
                  userPrompt,
                  schema,
                  timeout,
                  finalModels,
                  finalBudgetFallback));
    } catch (Exception ex) {
      if (ex instanceof RequestNotPermitted) {
        metrics.recordLlmRateLimitRejection();
        throw new TransientFailure(
            TransientFailure.LLM_RATE_LIMITED, "内容服务调用超过本地速率限制（令牌桶无可用许可），本次未发起请求，稍后自动重试", ex);
      }
      if (DownstreamResilience.isRejected(ex))
        throw new TransientFailure(
            TransientFailure.LLM_UNAVAILABLE,
            "内容服务熔断器已打开或并发已满（" + ex.getClass().getSimpleName() + "），本次未发起请求，稍后自动重试",
            ex);
      throw ex;
    }
  }

  private JsonNode requestJsonUnprotected(
      String taskId,
      String operation,
      PromptLibrary.Prompt prompt,
      String userPrompt,
      LlmJsonSchema.Schema schema,
      Duration timeout,
      List<String> models,
      boolean budgetFallback)
      throws Exception {
    Exception failure = null;
    for (int index = 0; index < models.size(); index++) {
      String current = models.get(index);
      try {
        return callOnce(
            taskId, current, operation, prompt, userPrompt, schema, timeout, budgetFallback);
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
      String taskId,
      String currentModel,
      String operation,
      PromptLibrary.Prompt prompt,
      String userPrompt,
      LlmJsonSchema.Schema schema,
      Duration timeout,
      boolean budgetFallback)
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
    JsonNode response =
        send(request, "内容服务", taskId, operation, prompt.id(), currentModel, budgetFallback);
    String content = response.path("choices").path(0).path("message").path("content").asText();
    StructuredOutput parsed = parseStructured(json, content, schema);
    if (!parsed.ok()) throw outputFailure(operation, parsed.reason(), parsed.message());
    return parsed.node();
  }

  /** 结构化闸门的判定结果：{@code node} 非空表示通过，否则 {@code reason} 为失败分类与可读消息。 */
  record StructuredOutput(JsonNode node, String reason, String message) {
    boolean ok() {
      return node != null;
    }

    static StructuredOutput accepted(JsonNode node) {
      return new StructuredOutput(node, null, null);
    }

    static StructuredOutput rejected(String reason, String message) {
      return new StructuredOutput(null, reason, message);
    }
  }

  /**
   * 结构化闸门：空内容 / 非法 JSON / 不符 schema 一律返回失败分类，供上层重试与打点。
   *
   * <p>做成静态方法并接收调用方的 {@link ObjectMapper}，是为了让 P1-1 的回放评测直接复用同一份实现： 评测与线上跑的是同一道闸门，而不是在评测里另写一份口径。
   */
  static StructuredOutput parseStructured(
      ObjectMapper json, String content, LlmJsonSchema.Schema schema) {
    if (content == null || content.isBlank())
      return StructuredOutput.rejected("empty_content", "内容服务未返回结果");
    JsonNode parsed;
    try {
      parsed = json.readTree(extractJson(content));
    } catch (Exception ex) {
      return StructuredOutput.rejected("invalid_json", "内容服务未返回有效 JSON");
    }
    List<String> violations = LlmJsonSchema.validate(parsed, schema.node());
    if (!violations.isEmpty())
      return StructuredOutput.rejected(
          "schema_violation", "返回结构不符合 JSON Schema：" + String.join("；", violations));
    return StructuredOutput.accepted(parsed);
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

  private JsonNode send(
      HttpRequest request,
      String service,
      String taskId,
      String operation,
      String promptId,
      String currentModel,
      boolean budgetFallback)
      throws Exception {
    boolean success = false;
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new LlmApiException(
            response.statusCode(),
            service + "返回 HTTP " + response.statusCode() + "：" + shortBody(response.body()));
      JsonNode body = json.readTree(response.body());
      recordUsage(taskId, operation, promptId, currentModel, budgetFallback, body);
      success = true;
      return body;
    } finally {
      metrics.recordLlmCall(operation, success);
    }
  }

  /**
   * 从响应 usage 字段累计 token 指标（按实际服务的模型归属）并落库，供按提示词版本与模型核算成本。
   *
   * <p>记账发生在结构校验之前：只要能拿到响应体，token 就已经真实消耗，结构非法只影响这次输出能不能用。
   */
  private void recordUsage(
      String taskId,
      String operation,
      String promptId,
      String currentModel,
      boolean budgetFallback,
      JsonNode response) {
    JsonNode usage = response.path("usage");
    if (!usage.isObject()) return;
    long promptTokens = usage.path("prompt_tokens").asLong();
    long completionTokens = usage.path("completion_tokens").asLong();
    metrics.recordLlmTokens(currentModel, promptTokens, completionTokens);
    usageLog.record(
        new LlmUsageRepository.LlmCall(
            taskId,
            operation,
            promptId,
            currentModel,
            budgetFallback,
            promptTokens,
            completionTokens));
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

  /**
   * 当日预算已用完且无廉价档可降级（非瞬时故障）。
   *
   * <p>不是「等一会儿会好」——在预算重置（自然日切换）之前重试多少次都会以同样方式失败， 因此按非瞬时异常处理：任务 FAILED、消息进死信队列，失败原因对用户可见。
   */
  static class BudgetExhaustedException extends IllegalStateException {
    BudgetExhaustedException(String message) {
      super(message);
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
