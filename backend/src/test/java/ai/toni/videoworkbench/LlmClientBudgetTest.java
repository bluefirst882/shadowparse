package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 日预算守卫与 LLM 调用的联动（P0 决策 #3）：预算超限后自动切到廉价档模型续跑并在账目里打 {@code budget_fallback} 标记；廉价档未配置或与主模型相同时抛
 * {@link LlmClient.BudgetExhaustedException} （非瞬时故障，任务 FAILED 而不是无限重试）；预算未启用时零开销、行为与现状一致。
 */
class LlmClientBudgetTest {
  private static final String PRIMARY = "flash-x";
  private static final String CHEAP = "cheap-x";

  private final ObjectMapper json = new ObjectMapper();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WorkbenchMetrics metrics = new WorkbenchMetrics(registry);
  private final LlmUsageRepository usageLog = Mockito.mock(LlmUsageRepository.class);
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void degradesToCheapModelWhenDailyBudgetIsExhausted() throws Exception {
    when(usageLog.totalTokensSince(any(Instant.class))).thenReturn(1_000_000L);
    LlmClient client = clientFor(guard(1000, CHEAP));

    TaskResult result = client.summarize("task-1", transcript(), 60000, null);

    assertEquals("摘要", result.summary());
    assertEquals(List.of(CHEAP), requestedModels(), "超预算后应只调用廉价档模型");
    assertEquals(1, registry.get(WorkbenchMetrics.LLM_BUDGET_FALLBACKS).counter().count());
    ArgumentCaptor<LlmUsageRepository.LlmCall> calls =
        ArgumentCaptor.forClass(LlmUsageRepository.LlmCall.class);
    verify(usageLog).record(calls.capture());
    assertEquals(CHEAP, calls.getValue().model());
    assertEquals(true, calls.getValue().budgetFallback(), "降级调用必须在账目里打 budget_fallback 标记");
  }

  @Test
  void failsInsteadOfDegradingWhenCheapModelIsNotConfigured() throws Exception {
    when(usageLog.totalTokensSince(any(Instant.class))).thenReturn(1_000_000L);
    LlmClient client = clientFor(guard(1000, ""));

    assertThrows(
        LlmClient.BudgetExhaustedException.class,
        () -> client.summarize("task-1", transcript(), 60000, null));

    assertEquals(0, requests.size(), "预算失败发生在容错外壳之前，不应向下游发起请求");
  }

  @Test
  void failsInsteadOfDegradingWhenCheapModelEqualsPrimary() throws Exception {
    when(usageLog.totalTokensSince(any(Instant.class))).thenReturn(1_000_000L);
    // 「降级」到与主模型相同的模型等于继续烧贵 token，必须按无廉价档处理。
    LlmClient client = clientFor(guard(1000, PRIMARY));

    assertThrows(
        LlmClient.BudgetExhaustedException.class,
        () -> client.summarize("task-1", transcript(), 60000, null));

    assertEquals(0, requests.size());
  }

  @Test
  void budgetCheckIsSkippedEntirelyWhenDisabled() throws Exception {
    // 预算关闭（0）时连账本查询都不该发生，行为与现状完全一致（回滚路径）。
    LlmClient client = clientFor(guard(0, CHEAP));

    client.summarize("task-1", transcript(), 60000, null);

    assertEquals(List.of(PRIMARY), requestedModels());
    Mockito.verify(usageLog, Mockito.never()).totalTokensSince(any(Instant.class));
    Mockito.verify(usageLog, Mockito.never()).userTokensSince(any(), any(Instant.class));
  }

  @Test
  void userBudgetTriggersDegradeEvenWhenGlobalBudgetIsUntouched() throws Exception {
    when(usageLog.userTokensSince(any(), any(Instant.class))).thenReturn(500L);
    LlmClient client = clientFor(guard(0, CHEAP, 100));

    client.summarize("task-1", transcript(), 60000, null);

    assertEquals(List.of(CHEAP), requestedModels(), "用户级预算触顶同样触发降级");
  }

  private LlmBudgetGuard guard(long dailyBudget, String cheapModel) {
    return guard(dailyBudget, cheapModel, 0);
  }

  private LlmBudgetGuard guard(long dailyBudget, String cheapModel, long userDailyBudget) {
    return new LlmBudgetGuard(usageLog, metrics, dailyBudget, userDailyBudget, cheapModel);
  }

  private List<String> requestedModels() {
    return requests.stream().map(request -> request.path("model").asText()).toList();
  }

  private static List<TranscriptSegment> transcript() {
    return List.of(new TranscriptSegment(1, 0, 1000, "你好", null));
  }

  private LlmClient clientFor(LlmBudgetGuard guard) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            JsonNode request =
                json.readTree(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            // usage 必须在响应里：记账发生在拿到响应体之后，没有 usage 就不会落账，
            // 而「降级调用带 budget_fallback 标记」正是要在账目里验证的。
            // usage 必须在响应里：记账发生在拿到响应体之后，没有 usage 就不会落账，
            // 而「降级调用带 budget_fallback 标记」正是要在账目里验证的。
            String content =
                json.writeValueAsString(
                    new TaskResult(
                        "摘要", List.of("要点"), List.of(new Chapter(0, 1000, "开场", 1, 1L, "你好"))));
            byte[] body =
                json.writeValueAsString(
                        Map.of(
                            "choices",
                            List.of(Map.of("message", Map.of("content", content))),
                            "usage",
                            Map.of("prompt_tokens", 100, "completion_tokens", 10)))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return new LlmClient(
        json,
        metrics,
        usageLog,
        DownstreamResilience.withDefaults(),
        guard,
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-key",
        PRIMARY,
        "",
        "low",
        90,
        180);
  }
}
