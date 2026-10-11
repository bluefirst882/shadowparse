package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 翻译批次完整性重试：LLM 偶发漏条（返回里缺某条或译文为空）只重试该批，不放弃整个任务。 生产事故：218 段翻译到某批 8 条时模型漏了一条，任务在转写全部完成后被判失败。 */
class LlmClientTranslateTest {
  private final ObjectMapper json = new ObjectMapper();
  private final List<String> requestPrompts = new CopyOnWriteArrayList<>();
  private final AtomicInteger responseIndex = new AtomicInteger();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void retriesIncompleteBatchAndSucceedsOnSecondAttempt() throws Exception {
    // 第 1 次响应缺 id=2，第 2 次补齐。
    LlmClient client =
        clientReturning(
            json.writeValueAsString(Map.of("translations", List.of(translation(1, "第一句")))),
            json.writeValueAsString(
                Map.of("translations", List.of(translation(1, "第一句"), translation(2, "第二句")))));
    List<TranscriptSegment> transcript =
        List.of(
            new TranscriptSegment(1, 0, 1000, "one", null),
            new TranscriptSegment(2, 1000, 2000, "two", null));

    List<TranscriptSegment> translated = client.translateToChinese("task-1", transcript);

    assertEquals(2, requestPrompts.size(), "缺条批次应重试一次而不是直接放弃");
    assertEquals(
        List.of("第一句", "第二句"), translated.stream().map(TranscriptSegment::translation).toList());
  }

  @Test
  void blankTranslationCountsAsIncompleteAndIsRefilled() throws Exception {
    // 第 1 次响应里 id=2 译文为空白，同样视为缺条；重试后拿到完整结果。
    LlmClient client =
        clientReturning(
            json.writeValueAsString(
                Map.of("translations", List.of(translation(1, "第一句"), translation(2, "  ")))),
            json.writeValueAsString(
                Map.of("translations", List.of(translation(1, "第一句"), translation(2, "第二句")))));
    List<TranscriptSegment> transcript =
        List.of(
            new TranscriptSegment(1, 0, 1000, "one", null),
            new TranscriptSegment(2, 1000, 2000, "two", null));

    List<TranscriptSegment> translated = client.translateToChinese("task-1", transcript);

    assertEquals("第二句", translated.get(1).translation());
    assertEquals(2, requestPrompts.size());
  }

  @Test
  void givesUpAfterThreeAttemptsWhenBatchStaysIncomplete() throws Exception {
    LlmClient client =
        clientReturning(
            json.writeValueAsString(Map.of("translations", List.of(translation(1, "第一句")))),
            json.writeValueAsString(Map.of("translations", List.of(translation(1, "第一句")))),
            json.writeValueAsString(Map.of("translations", List.of(translation(1, "第一句")))));
    List<TranscriptSegment> transcript =
        List.of(
            new TranscriptSegment(1, 0, 1000, "one", null),
            new TranscriptSegment(2, 1000, 2000, "two", null));

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> client.translateToChinese("task-1", transcript));

    assertTrue(ex.getMessage().contains("本批应返回 2 条"), "失败信息应说明该批应有的条数");
    assertEquals(3, requestPrompts.size(), "单批最多尝试 3 次");
  }

  private static Map<String, Object> translation(long id, String text) {
    return Map.of("id", id, "translation", text);
  }

  private LlmClient clientReturning(String... responseContents) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            JsonNode request =
                json.readTree(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestPrompts.add(request.path("messages").path(1).path("content").asText());
            String content = responseContents[responseIndex.getAndIncrement()];
            String envelope =
                json.writeValueAsString(
                    Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
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
        new WorkbenchMetrics(new SimpleMeterRegistry()),
        Mockito.mock(LlmUsageRepository.class),
        DownstreamResilience.withDefaults(),
        new LlmBudgetGuard(
            Mockito.mock(LlmUsageRepository.class),
            new WorkbenchMetrics(new SimpleMeterRegistry()),
            0,
            0,
            ""),
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-key",
        "test-model",
        "",
        "low",
        90,
        180);
  }
}
