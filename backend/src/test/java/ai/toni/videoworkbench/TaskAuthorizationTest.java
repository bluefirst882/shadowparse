package ai.toni.videoworkbench;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskController.class)
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtService.class,
  ApiErrorWriter.class
})
@TestPropertySource(
    properties = "workbench.security.jwt-secret=test-secret-test-secret-test-secret-1234")
class TaskAuthorizationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwt;

  @MockitoBean private TaskService service;
  @MockitoBean private ExportService exports;

  @Test
  void rejectsRequestWithoutToken() throws Exception {
    mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsRequestWithInvalidToken() throws Exception {
    mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void servesOnlyOwnTasks() throws Exception {
    when(service.list("user-a", null, 20)).thenReturn(new TaskPage(List.of(), null));

    mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer("user-a")))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsTaskOwnedByAnotherUser() throws Exception {
    when(service.get(eq("task-1"), eq("user-b"))).thenThrow(new AccessDeniedException("无权访问该任务"));

    mvc.perform(get("/api/tasks/task-1").header(HttpHeaders.AUTHORIZATION, bearer("user-b")))
        .andExpect(status().isForbidden());
  }

  private String bearer(String userId) {
    return "Bearer " + jwt.issue(new AuthenticatedUser(userId, userId));
  }
}
