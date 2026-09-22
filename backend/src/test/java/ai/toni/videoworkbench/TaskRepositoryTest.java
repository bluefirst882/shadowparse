package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class TaskRepositoryTest {
  private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
  private final TaskRepository repository = new TaskRepository(jdbc, new ObjectMapper());

  @Test
  void claimsOnlyQueuedUncancelledTask() {
    when(jdbc.update(any(String.class), any(), eq("task-1"))).thenReturn(1);

    assertTrue(repository.claimForProcessing("task-1"));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("status='QUEUED' and cancelled=false"),
            any(),
            eq("task-1"));
  }

  @Test
  void cancelsOnlyQueuedOrProcessingTask() {
    when(jdbc.update(any(String.class), any(), eq("task-1"))).thenReturn(1);

    assertTrue(repository.cancel("task-1"));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("status in ('QUEUED','PROCESSING')"),
            any(),
            eq("task-1"));
  }

  @Test
  void replacesTranscriptAndInvalidatesItsPreviousResultInOneTransaction() throws Exception {
    repository.replaceSegments("task-1", List.of(new TranscriptSegment(0, 0, 1000, "新的转写", null)));

    InOrder ordered = inOrder(jdbc);
    ordered.verify(jdbc).update("delete from transcript_segments where task_id=?", "task-1");
    ordered
        .verify(jdbc)
        .update(any(String.class), eq("task-1"), eq(0L), eq(1000L), eq("新的转写"), eq(null));
    ordered.verify(jdbc).update("delete from task_results where task_id=?", "task-1");
    Method method =
        TaskRepository.class.getDeclaredMethod("replaceSegments", String.class, List.class);
    assertTrue(method.isAnnotationPresent(Transactional.class));
  }
}
