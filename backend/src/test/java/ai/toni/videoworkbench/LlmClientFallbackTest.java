package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 用本地 HTTP 替身验证 P1-3 的模型降级链：主模型报错、超时类错误或输出结构不可用时升级到备用模型，
 * 并保证降级事件按原因打点、日志可查；未配置备用模型时保持原行为（直接失败，不静默重试）。
 */
class LlmClientFallbackTest {
  private static final String PRIMARY = "flash-x";
  private static final String FALLBACK = "pro-x";

  private final ObjectMapper json = new ObjectMapper();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void upgradesToFallbackModelWhenPrimaryReturnsHttpError() throws Exception {
    LlmClient client =
        clientFor(
            PRIMARY,
            FALLBACK,
            model ->
                model.equals(PRIMARY)
                    ? new Stub(400, "model not found")
                    : new Stub(200, validSummary()));

    TaskResult result = client.summarize("task-1", transcript(), 60000, null);

    assertEquals("摘要", result.summary());
    assertEquals(List.of(PRIMARY, FALLBACK), requestedModels());
    assertEquals(1, fallbackCount("http_400"));
    // 主模型的失败与备用模型的成功各记一次，成功率不会被降级掩盖。
    assertEquals(1, requestCount("failure"));
    assertEquals(1, requestCount("success"));
  }

  @Test
  void upgradesWhenPrimaryOutputViolatesSchema() throws Exception {
    String missingQuote =
        json.writeValueAsString(
            Map.of(
                "summary",
                "摘要",
                "keyPoints",
                List.of("要点"),
                "chapters",
                List.of(
                    Map.of(
                        "startMs",
                        0,
                        "endMs",
                        1000,
                        "title",
                        "开场",
                        "sourceSegmentId",
                        1,
                        "sourceEndSegmentId",
                        1))));
    LlmClient client =
        clientFor(
            PRIMARY,
            FALLBACK,
            model -> new Stub(200, model.equals(PRIMARY) ? missingQuote : validSummary()));

    TaskResult result = client.summarize("task-1", transcript(), 60000, null);

    assertEquals("摘要", result.summary());
    assertEquals(List.of(PRIMARY, FALLBACK), requestedModels());
    assertEquals(1, fallbackCount("schema_violation"));
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_OUTPUT_FAILURES)
            .tags("operation", "summarize", "reason", "schema_violation")
            .counter()
            .count());
  }

  @Test
  void upgradesWhenPrimaryReturnsEmptyContent() throws Exception {
    LlmClient client =
        clientFor(
            PRIMARY, FALLBACK, model -> new Stub(200, model.equals(PRIMARY) ? "" : validSummary()));

    client.summarize("task-1", transcript(), 60000, null);

    assertEquals(1, fallbackCount("empty_content"));
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_OUTPUT_FAILURES)
            .tags("operation", "summarize", "reason", "empty_content")
            .counter()
            .count());
  }

  @Test
  void reportsFailureWhenFallbackModelAlsoFails() throws Exception {
    LlmClient client = clientFor(PRIMARY, FALLBACK, model -> new Stub(500, "boom"));

    LlmClient.LlmApiException failure =
        assertThrows(
            LlmClient.LlmApiException.class,
            () -> client.summarize("task-1", transcript(), 60000, null));

    assertEquals(500, failure.status());
    assertEquals(List.of(PRIMARY, FALLBACK), requestedModels());
    assertEquals(1, fallbackCount("http_500"));
  }

  @Test
  void doesNotFallBackWhenNoFallbackModelConfigured() throws Exception {
    LlmClient client = clientFor(PRIMARY, "", model -> new Stub(500, "boom"));

    assertThrows(
        LlmClient.LlmApiException.class,
        () -> client.summarize("task-1", transcript(), 60000, null));

    assertEquals(List.of(PRIMARY), requestedModels());
    assertNull(registry.find(WorkbenchMetrics.LLM_FALLBACKS).counter());
  }

  @Test
  void doesNotFallBackToTheSameModel() throws Exception {
    LlmClient client = clientFor(PRIMARY, PRIMARY, model -> new Stub(500, "boom"));

    assertThrows(
        LlmClient.LlmApiException.class,
        () -> client.summarize("task-1", transcript(), 60000, null));

    assertEquals(List.of(PRIMARY), requestedModels());
    assertNull(registry.find(WorkbenchMetrics.LLM_FALLBACKS).counter());
  }

  private double fallbackCount(String reason) {
    return registry
        .get(WorkbenchMetrics.LLM_FALLBACKS)
        .tags("operation", "summarize", "reason", reason)
        .counter()
        .count();
  }

  private double requestCount(String outcome) {
    return registry
        .get(WorkbenchMetrics.LLM_REQUESTS)
        .tags("operation", "summarize", "outcome", outcome)
        .counter()
        .count();
  }

  private List<String> requestedModels() {
    return requests.stream().map(request -> request.path("model").asText()).toList();
  }

  private static List<TranscriptSegment> transcript() {
    return List.of(new TranscriptSegment(1, 0, 1000, "你好", null));
  }

  private String validSummary() {
    try {
      return json.writeValueAsString(
          new TaskResult("摘要", List.of("要点"), List.of(new Chapter(0, 1000, "开场", 1, 1L, "你好"))));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private LlmClient clientFor(String primary, String fallback, Function<String, Stub> responses)
      throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            JsonNode request =
                json.readTree(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            Stub stub = responses.apply(request.path("model").asText());
            String envelope =
                stub.status() == 200
                    ? json.writeValueAsString(
                        Map.of(
                            "choices",
                            List.of(Map.of("message", Map.of("content", stub.content())))))
                    : json.writeValueAsString(Map.of("error", Map.of("message", stub.content())));
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), body.length);
            exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return new LlmClient(
        json,
        new WorkbenchMetrics(registry),
        Mockito.mock(LlmUsageRepository.class),
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-key",
        primary,
        fallback,
        "low");
  }

  /** HTTP 替身的一次应答：{@code status} 非 200 时按错误体返回。 */
  private record Stub(int status, String content) {}
}
