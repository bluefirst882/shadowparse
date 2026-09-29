package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 覆盖列表接口的分页参数边界：非法 limit / cursor 一律 400，合法请求把游标与 limit 透传给服务。 */
@WebMvcTest(TaskController.class)
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtService.class,
  ApiErrorWriter.class
})
@TestPropertySource(
    properties = "workbench.security.jwt-secret=test-secret-test-secret-test-secret-1234")
class TaskListPaginationTest {
  private static final Instant CREATED_AT = Instant.ofEpochMilli(1_700_000_000_123L);

  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwt;
  @Autowired private ObjectMapper json;

  @MockitoBean private TaskService service;
  @MockitoBean private ExportService exports;
  @MockitoBean private LlmCostService costs;
  @MockitoBean private TaskEventStream events;
  @MockitoBean private ChunkedUploadService uploads;

  @Test
  void returnsItemsAndNextCursorForFirstPage() throws Exception {
    when(service.list("user-a", null, 20))
        .thenReturn(new TaskPage(List.of(task("task-1")), "next-cursor"));

    MvcResult result = perform("/api/tasks");

    JsonNode body = body(result);
    assertEquals("task-1", body.path("items").get(0).path("id").asText());
    assertEquals("next-cursor", body.path("nextCursor").asText());
  }

  @Test
  void returnsNullNextCursorWhenThereIsNoMoreData() throws Exception {
    when(service.list("user-a", null, 20)).thenReturn(new TaskPage(List.of(), null));

    JsonNode body = body(perform("/api/tasks"));

    assertTrue(body.path("items").isArray());
    assertTrue(body.path("nextCursor").isNull());
  }

  @Test
  void passesDecodedCursorAndRequestedLimitToService() throws Exception {
    String cursor = new TaskCursor(CREATED_AT, "task-9").encode();
    when(service.list("user-a", new TaskCursor(CREATED_AT, "task-9"), 5))
        .thenReturn(new TaskPage(List.of(), null));

    perform("/api/tasks?cursor=" + cursor + "&limit=5");

    verify(service).list("user-a", new TaskCursor(CREATED_AT, "task-9"), 5);
  }

  @Test
  void rejectsNonNumericLimit() throws Exception {
    assertBadRequest("/api/tasks?limit=abc", "limit 必须为整数");
  }

  @Test
  void rejectsZeroLimit() throws Exception {
    assertBadRequest("/api/tasks?limit=0", "limit 必须大于 0");
  }

  @Test
  void rejectsNegativeLimit() throws Exception {
    assertBadRequest("/api/tasks?limit=-1", "limit 必须大于 0");
  }

  @Test
  void rejectsLimitAboveMaximum() throws Exception {
    assertBadRequest("/api/tasks?limit=101", "limit 不能超过 100");
  }

  @Test
  void rejectsCursorWithoutIdField() throws Exception {
    assertBadRequest("/api/tasks?cursor=" + encode("1700000000000"), "cursor 参数无效");
  }

  @Test
  void rejectsCursorWithUnparsableTimestamp() throws Exception {
    assertBadRequest("/api/tasks?cursor=" + encode("not-a-number|task-1"), "cursor 参数无效");
  }

  @Test
  void rejectsCursorThatIsNotBase64() throws Exception {
    assertBadRequest("/api/tasks?cursor=not%20base64!!", "cursor 参数无效");
  }

  private void assertBadRequest(String path, String message) throws Exception {
    MvcResult result =
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
            .andExpect(status().isBadRequest())
            .andReturn();

    JsonNode body = body(result);
    assertEquals("INVALID_REQUEST", body.path("code").asText());
    assertEquals(message, body.path("message").asText());
    verify(service, never()).list(any(), any(), anyInt());
  }

  private MvcResult perform(String path) throws Exception {
    return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
        .andExpect(status().isOk())
        .andReturn();
  }

  private VideoTask task(String id) {
    return new VideoTask(
        id,
        "video.mp4",
        "test://" + id,
        0,
        TaskStatus.QUEUED,
        TaskStage.IMPORT,
        0,
        null,
        CREATED_AT,
        CREATED_AT);
  }

  private String encode(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String bearer(String userId) {
    return "Bearer " + jwt.issue(new AuthenticatedUser(userId, userId));
  }
}
