package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 用本地 HTTP 替身验证 LlmClient 会解析响应 {@code usage} 并打点、按提示词版本与模型落库，避免依赖真实云端模型。
 *
 * <p>覆盖成功（记录 prompt/completion token、成功计数与账目）与失败（HTTP 非 2xx 记失败）两条路径。
 */
class LlmClientMetricsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ObjectMapper json = new ObjectMapper();
  private final LlmUsageRepository usageLog = Mockito.mock(LlmUsageRepository.class);
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void recordsTokensAndSuccessFromUsageField() throws Exception {
    LlmClient client = clientFor(200, successResponse(123, 45));

    client.summarize("task-1", List.of(new TranscriptSegment(1, 0, 1000, "你好", null)), 60000, null);

    assertEquals(
        123,
        registry
            .get(WorkbenchMetrics.LLM_TOKENS)
            .tags("type", "prompt", "model", "test-model")
            .counter()
            .count());
    assertEquals(
        45,
        registry
            .get(WorkbenchMetrics.LLM_TOKENS)
            .tags("type", "completion", "model", "test-model")
            .counter()
            .count());
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_REQUESTS)
            .tags("operation", "summarize", "outcome", "success")
            .counter()
            .count());
    // 账目要能回答「哪个视频、哪版提示词、哪个模型、多少 token」，所以四项缺一不可。
    Mockito.verify(usageLog)
        .record(
            new LlmUsageRepository.LlmCall(
                "task-1", "summarize", "summarize.v1", "test-model", 123, 45));
  }

  @Test
  void recordsFailureWhenServiceRespondsWithError() throws Exception {
    LlmClient client = clientFor(500, "{\"error\":\"boom\"}");

    assertThrows(
        IllegalStateException.class,
        () ->
            client.summarize(
                "task-1", List.of(new TranscriptSegment(1, 0, 1000, "你好", null)), 60000, null));

    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_REQUESTS)
            .tags("operation", "summarize", "outcome", "failure")
            .counter()
            .count());
    assertNull(registry.find(WorkbenchMetrics.LLM_TOKENS).tag("type", "prompt").counter());
    Mockito.verifyNoInteractions(usageLog);
  }

  private LlmClient clientFor(int status, String responseBody) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return new LlmClient(
        new ObjectMapper(),
        new WorkbenchMetrics(registry),
        usageLog,
        DownstreamResilience.withDefaults(),
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-key",
        "test-model",
        "",
        "low",
        90,
        180);
  }

  private String successResponse(long promptTokens, long completionTokens) throws Exception {
    // 引文是必填字段：模型输出先过 JSON Schema 校验，缺 quote 会被判结构违规。
    String content =
        json.writeValueAsString(
            new TaskResult("摘要", List.of("要点"), List.of(new Chapter(0, 1000, "开场", 1, 1L, "你好"))));
    Map<String, Object> body =
        Map.of(
            "choices",
            List.of(Map.of("message", Map.of("content", content))),
            "usage",
            Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens));
    return json.writeValueAsString(body);
  }
}
