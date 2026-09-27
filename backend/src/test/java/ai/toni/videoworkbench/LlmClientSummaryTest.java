package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 用本地 HTTP 替身验证 P1-6 的跨块二次合并与要点去重/不截断行为，不依赖真实云端模型。
 *
 * <p>替身按调用顺序返回预设响应：分块调用返回各块结果，合并调用返回合并结果；同时记录每次请求的提示词， 用于断言合并调用确实发生、且上一次的校验失败原因被回灌到提示词里。
 */
class LlmClientSummaryTest {
  private final ObjectMapper json = new ObjectMapper();
  private final List<String> requestPrompts = new CopyOnWriteArrayList<>();
  private final AtomicInteger responseIndex = new AtomicInteger();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void mergesMultipleChunksWithOneExtraCallAndDoesNotTruncateKeyPoints() throws Exception {
    List<String> mergedPoints = new ArrayList<>();
    for (int i = 1; i <= 13; i++) mergedPoints.add("P" + i);
    mergedPoints.add("P1。");
    mergedPoints.add("P2 ");
    LlmClient client =
        clientReturning(
            chunkResponse("块一摘要", List.of("要点A", "要点A。"), 1, 1000, 2000),
            chunkResponse("块二摘要", List.of("要点B"), 2, 2000, 3000),
            mergeResponse("合并摘要", mergedPoints));
    List<TranscriptSegment> transcript =
        List.of(
            new TranscriptSegment(1, 1000, 2000, "一", null),
            new TranscriptSegment(2, 2000, 3000, "二", null));

    TaskResult result =
        client.summarize(transcript, limitForSingleSegment(transcript), "第 1 个章节引文不符");

    assertEquals(3, requestPrompts.size(), "应为 2 次分块调用 + 1 次合并调用");
    assertTrue(requestPrompts.getLast().contains("合并去重"), "最后一次调用应是合并提示词");
    assertTrue(
        requestPrompts.stream().allMatch(prompt -> prompt.contains("上次输出未通过校验")),
        "重试时应把上一次的校验失败原因回灌到每次提示词");
    assertEquals(13, result.keyPoints().size(), "超过告警阈值 12 的要点必须全部保留，不得截断");
    assertEquals(List.of("开场", "结尾"), result.chapters().stream().map(Chapter::title).toList());
    assertEquals("合并摘要", result.summary());
  }

  @Test
  void skipsMergeCallAndDedupesWithinSingleChunk() throws Exception {
    LlmClient client =
        clientReturning(chunkResponse("块摘要", List.of("要点A", "要点A。", " 要点A "), 1, 0, 1000));
    List<TranscriptSegment> transcript = List.of(new TranscriptSegment(1, 0, 1000, "一", null));

    TaskResult result = client.summarize(transcript, 60000, null);

    assertEquals(1, requestPrompts.size(), "单块不应额外发起合并调用");
    assertFalse(requestPrompts.getFirst().contains("合并去重"));
    assertEquals(List.of("要点A"), result.keyPoints(), "仅标点/空白不同的要点应被合并");
  }

  @Test
  void dedupeKeyPointsKeepsFirstOccurrenceAndOrder() {
    List<String> deduped =
        LlmClient.dedupeKeyPoints(List.of("要点A", "要点A。", " 要点A ", "要点B", "要点B！"));

    assertEquals(List.of("要点A", "要点B"), deduped);
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
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-key",
        "test-model",
        "",
        "low");
  }

  private String chunkResponse(
      String summary, List<String> keyPoints, long id, long startMs, long endMs) throws Exception {
    String title = id == 1 ? "开场" : "结尾";
    return json.writeValueAsString(
        new TaskResult(
            summary, keyPoints, List.of(new Chapter(startMs, endMs, title, id, id, "引文" + id))));
  }

  private String mergeResponse(String summary, List<String> keyPoints) throws Exception {
    return json.writeValueAsString(Map.of("summary", summary, "keyPoints", keyPoints));
  }

  private static int limitForSingleSegment(List<TranscriptSegment> transcript) {
    TranscriptSegment first = transcript.getFirst();
    int firstLineChars =
        ("[id="
                    + first.id()
                    + ", startMs="
                    + first.startMs()
                    + ", endMs="
                    + first.endMs()
                    + "] "
                    + first.text())
                .length()
            + 1;
    return firstLineChars + 1;
  }
}
