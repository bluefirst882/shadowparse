package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class TaskServiceTest {
  private static final String OWNER = "user-a";

  private final TaskRepository tasks = Mockito.mock(TaskRepository.class);
  private final LlmClient llm = Mockito.mock(LlmClient.class);

  @Test
  void requeuesQueuedTaskThatWasNotPreviouslyScheduled() {
    TaskService service = service();
    when(tasks.findOwned("task-1", OWNER)).thenReturn(Optional.of(task(TaskStatus.QUEUED)));
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.QUEUED)));
    when(tasks.claimForProcessing("task-1")).thenReturn(false);

    assertDoesNotThrow(() -> service.retry("task-1", OWNER));

    verify(tasks, timeout(1000)).claimForProcessing("task-1");
    verify(tasks, never()).reset(any(), any());
  }

  @Test
  void rejectsDuplicateRetryWhileQueuedTaskIsAlreadyScheduled() throws Exception {
    TaskService service = service();
    CountDownLatch claimStarted = new CountDownLatch(1);
    CountDownLatch releaseClaim = new CountDownLatch(1);
    when(tasks.findOwned("task-1", OWNER)).thenReturn(Optional.of(task(TaskStatus.QUEUED)));
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.QUEUED)));
    when(tasks.claimForProcessing("task-1"))
        .thenAnswer(
            ignored -> {
              claimStarted.countDown();
              releaseClaim.await(1, TimeUnit.SECONDS);
              return false;
            });

    service.retry("task-1", OWNER);
    org.junit.jupiter.api.Assertions.assertTrue(claimStarted.await(1, TimeUnit.SECONDS));
    ResponseStatusException error =
        assertThrows(ResponseStatusException.class, () -> service.retry("task-1", OWNER));
    releaseClaim.countDown();

    org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
  }

  @Test
  void deniesOperationOnTaskOwnedByAnotherUser() {
    TaskService service = service();
    when(tasks.findOwned("task-1", "user-b")).thenReturn(Optional.empty());

    assertThrows(AccessDeniedException.class, () -> service.cancel("task-1", "user-b"));
    assertThrows(AccessDeniedException.class, () -> service.details("task-1", "user-b"));

    verify(tasks, never()).cancel("task-1");
    verify(tasks, never()).segments("task-1");
  }

  @Test
  void ignoresQueueEntryForTaskDeletedBeforeProcessing() throws Exception {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.empty());

    Method process = TaskService.class.getDeclaredMethod("process", String.class);
    process.setAccessible(true);
    assertDoesNotThrow(() -> process.invoke(service, "task-1"));

    verify(tasks).find("task-1");
    verify(tasks, never()).claimForProcessing("task-1");
  }

  @Test
  void removesUploadedFileWhenTaskPersistenceFails() throws Exception {
    Path storage = Files.createTempDirectory("task-service-import-test");
    try {
      TaskService service = service(storage);
      when(tasks.find(any())).thenReturn(Optional.empty());
      org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
          .when(tasks)
          .save(any(), any());

      assertThrows(
          IllegalStateException.class,
          () ->
              service.importVideo(
                  new MockMultipartFile(
                      "file", "failed-save.mp4", "video/mp4", new byte[] {1, 2, 3}),
                  OWNER));

      try (var files = Files.list(storage)) {
        org.junit.jupiter.api.Assertions.assertEquals(0, files.count());
      }
    } finally {
      Files.deleteIfExists(storage);
    }
  }

  @Test
  void savesTaskWithRetryHintWhenQueueIsFull() throws Exception {
    Path storage = Files.createTempDirectory("task-service-queue-test");
    CountDownLatch claimStarted = new CountDownLatch(1);
    CountDownLatch releaseClaim = new CountDownLatch(1);
    TaskService service = service(storage);
    try {
      when(tasks.findOwned("running", OWNER))
          .thenReturn(Optional.of(task("running", TaskStatus.QUEUED)));
      when(tasks.findOwned("waiting", OWNER))
          .thenReturn(Optional.of(task("waiting", TaskStatus.QUEUED)));
      when(tasks.find("running")).thenReturn(Optional.of(task("running", TaskStatus.QUEUED)));
      when(tasks.find("waiting")).thenReturn(Optional.of(task("waiting", TaskStatus.QUEUED)));
      when(tasks.claimForProcessing("running"))
          .thenAnswer(
              ignored -> {
                claimStarted.countDown();
                releaseClaim.await(1, TimeUnit.SECONDS);
                return false;
              });
      when(tasks.claimForProcessing("waiting")).thenReturn(false);

      service.retry("running", OWNER);
      org.junit.jupiter.api.Assertions.assertTrue(claimStarted.await(1, TimeUnit.SECONDS));
      service.retry("waiting", OWNER);
      VideoTask imported =
          service.importVideo(
              new MockMultipartFile("file", "queued.mp4", "video/mp4", new byte[] {1, 2, 3}),
              OWNER);

      org.junit.jupiter.api.Assertions.assertEquals(TaskStatus.QUEUED, imported.status());
      org.junit.jupiter.api.Assertions.assertTrue(imported.errorMessage().contains("队列已满"));
      verify(tasks, times(1)).save(any(), eq(OWNER));
      verify(tasks)
          .update(
              any(), org.mockito.ArgumentMatchers.eq(TaskStatus.QUEUED), any(), anyInt(), any());
    } finally {
      releaseClaim.countDown();
      service.stop();
      try (var files = Files.list(storage)) {
        files.forEach(
            path -> {
              try {
                Files.deleteIfExists(path);
              } catch (java.io.IOException ignored) {
              }
            });
      }
      Files.deleteIfExists(storage);
    }
  }

  private TaskService service() {
    return service(Path.of("target/task-service-test-storage"));
  }

  private TaskService service(Path storage) {
    return new TaskService(
        tasks,
        new ObjectMapper(),
        llm,
        new ResultValidator(),
        new WorkbenchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
        storage.toString(),
        "ffmpeg",
        "http://127.0.0.1:8090",
        "test-token-test-token-test-token-test-token",
        "turbo",
        "",
        1024,
        1,
        60000,
        1);
  }

  private VideoTask task(TaskStatus status) {
    return task("task-1", status);
  }

  private VideoTask task(String id, TaskStatus status) {
    Instant now = Instant.now();
    return new VideoTask(
        id, "video.mp4", "video.mp4", 1, status, TaskStage.IMPORT, 0, null, now, now);
  }
}
