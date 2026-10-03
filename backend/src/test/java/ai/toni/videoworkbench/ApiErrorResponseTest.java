package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(TaskController.class)
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtService.class,
  ApiErrorWriter.class
})
@TestPropertySource(
    properties = "workbench.security.jwt-secret=test-secret-test-secret-test-secret-1234")
class ApiErrorResponseTest {
  private static final String TRACE_ID = "X-Trace-Id";
  private static final String TRACE_ID_PATTERN = "[0-9a-f]{32}";

  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwt;
  @Autowired private ObjectMapper json;

  @MockitoBean private TaskService service;
  @MockitoBean private ExportService exports;
  @MockitoBean private LlmCostService costs;
  @MockitoBean private TaskEventStream events;
  @MockitoBean private ChunkedUploadService uploads;

  @Test
  void returnsUnauthorizedWithTraceIdForMissingToken() throws Exception {
    MvcResult result =
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized()).andReturn();

    JsonNode body = body(result);
    assertEquals("UNAUTHORIZED", body.path("code").asText());
    assertTraceIdConsistent(result, body);
  }

  @Test
  void returnsForbiddenForTaskOwnedByAnotherUser() throws Exception {
    when(service.get(eq("task-1"), eq("user-b"))).thenThrow(new AccessDeniedException("无权访问该任务"));

    MvcResult result =
        mvc.perform(get("/api/tasks/task-1").header(HttpHeaders.AUTHORIZATION, bearer("user-b")))
            .andExpect(status().isForbidden())
            .andReturn();

    JsonNode body = body(result);
    assertEquals("FORBIDDEN", body.path("code").asText());
    assertEquals("无权访问该任务", body.path("message").asText());
    assertTraceIdConsistent(result, body);
  }

  @Test
  void returnsBadRequestForUnsupportedExportFormat() throws Exception {
    when(exports.export(eq("task-1"), eq("pdf"), eq("user-a")))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的导出格式"));

    MvcResult result =
        mvc.perform(
                get("/api/tasks/task-1/export/pdf")
                    .header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
            .andExpect(status().isBadRequest())
            .andReturn();

    JsonNode body = body(result);
    assertEquals("INVALID_REQUEST", body.path("code").asText());
    assertEquals("不支持的导出格式", body.path("message").asText());
    assertTraceIdConsistent(result, body);
  }

  @Test
  void preservesStatusCodeThatErrorCodeDoesNotCover() throws Exception {
    when(service.list("user-a", null, 20))
        .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "无法处理的请求"));

    MvcResult result =
        mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();

    JsonNode body = body(result);
    // code 只做粗分类，状态码必须以异常为准，不能被回落值改写成 400。
    assertEquals("INVALID_REQUEST", body.path("code").asText());
    assertEquals("无法处理的请求", body.path("message").asText());
    assertTraceIdConsistent(result, body);
  }

  @Test
  void keepsSpringClientErrorsOutOfTheServerErrorBucket() throws Exception {
    // 路径不存在这类 Spring 自带错误属于客户端错误，兜底处理器不能把它们统一变成 500。
    when(service.list("user-a", null, 20))
        .thenThrow(new ErrorResponseException(HttpStatus.NOT_FOUND));

    MvcResult result =
        mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
            .andExpect(status().isNotFound())
            .andReturn();

    JsonNode body = body(result);
    assertEquals("NOT_FOUND", body.path("code").asText());
    assertTraceIdConsistent(result, body);
  }

  @Test
  void allowsCrossOriginChunkUploadWithPut() throws Exception {
    // 浏览器对同源的写请求同样会带 Origin 头，一旦 PUT 不在 CORS 方法白名单里就会被判成
    // 「Invalid CORS request」返回 403（分片上传因此整体失败），所以这里固定住「带 Origin 的 PUT 必须放行」。
    when(uploads.putChunk(eq("upload-1"), eq("user-a"), eq(0), any(byte[].class)))
        .thenReturn(new ChunkedUploadService.Session("upload-1", "clip.mp4", 4, 5, List.of(0)));

    mvc.perform(
            put("/api/tasks/uploads/upload-1/chunks/0")
                .header(HttpHeaders.AUTHORIZATION, bearer("user-a"))
                .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5174")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[] {1, 2, 3, 4}))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5174"));
  }

  @Test
  void allowsCrossOriginRenameWithPatch() throws Exception {
    // 任务改名走 PATCH：与 PUT 同理，方法不在 CORS 白名单里时带 Origin 的请求会 403，这里固定住。
    when(service.rename(eq("task-1"), eq("user-a"), eq("新名字")))
        .thenReturn(
            new VideoTask(
                "task-1",
                "a.mp4",
                "新名字",
                "/storage/a.mp4",
                1,
                TaskStatus.COMPLETED,
                TaskStage.COMPLETED,
                100,
                null,
                Instant.now(),
                Instant.now()));

    mvc.perform(
            patch("/api/tasks/task-1/name")
                .header(HttpHeaders.AUTHORIZATION, bearer("user-a"))
                .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5174")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新名字\"}"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5174"));
  }

  @Test
  void returnsBadRequestWhenUploadIsNotMultipart() throws Exception {
    // 非 multipart 请求打到上传接口会抛 MultipartException，属于客户端错误，不能落成 500。
    MvcResult result =
        mvc.perform(
                post("/api/tasks")
                    .header(HttpHeaders.AUTHORIZATION, bearer("user-a"))
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("not a multipart body"))
            .andExpect(status().isBadRequest())
            .andReturn();

    JsonNode body = body(result);
    assertEquals("INVALID_REQUEST", body.path("code").asText());
    assertTraceIdConsistent(result, body);
  }

  @Test
  void returnsInternalServerErrorWithoutLeakingExceptionDetails() throws Exception {
    when(service.list("user-a", null, 20))
        .thenThrow(new IllegalStateException("secret internal detail"));

    MvcResult result =
        mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
            .andExpect(status().isInternalServerError())
            .andReturn();

    JsonNode body = body(result);
    String message = body.path("message").asText();
    assertEquals("INTERNAL_ERROR", body.path("code").asText());
    assertFalse(message.contains("secret internal detail"));
    assertFalse(message.contains("IllegalStateException"));
    assertTrue(body.path("traceId").asText().matches(TRACE_ID_PATTERN));
  }

  @Test
  void includesGeneratedTraceIdOnSuccessfulResponses() throws Exception {
    when(service.list("user-a", null, 20)).thenReturn(new TaskPage(List.of(), null));

    MvcResult result =
        mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
            .andExpect(status().isOk())
            .andReturn();

    assertTrue(result.getResponse().getHeader(TRACE_ID).matches(TRACE_ID_PATTERN));
  }

  @Test
  void reusesValidIncomingTraceIdHeader() throws Exception {
    when(service.list("user-a", null, 20)).thenReturn(new TaskPage(List.of(), null));

    MvcResult result =
        mvc.perform(
                get("/api/tasks")
                    .header(HttpHeaders.AUTHORIZATION, bearer("user-a"))
                    .header(TRACE_ID, "given-trace-123"))
            .andExpect(status().isOk())
            .andReturn();

    assertEquals("given-trace-123", result.getResponse().getHeader(TRACE_ID));
  }

  private void assertTraceIdConsistent(MvcResult result, JsonNode body) {
    assertTrue(body.path("traceId").asText().matches(TRACE_ID_PATTERN));
    assertEquals(body.path("traceId").asText(), result.getResponse().getHeader(TRACE_ID));
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String bearer(String userId) {
    return "Bearer " + jwt.issue(new AuthenticatedUser(userId, userId));
  }
}
