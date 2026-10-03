package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
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
  private final TaskQueue queue = Mockito.mock(TaskQueue.class);

  @Test
  void publishesQueuedTaskForRetryWithoutResetting() {
    TaskService service = service();
    when(tasks.findOwned("task-1", OWNER)).thenReturn(Optional.of(task(TaskStatus.QUEUED)));

    assertDoesNotThrow(() -> service.retry("task-1", OWNER));

    verify(queue).publish("task-1");
    verify(tasks, never()).reset(any(), any());
  }

  @Test
  void reportsServiceUnavailableWhenRetryCannotBePublished() {
    TaskService service = service();
    when(tasks.findOwned("task-1", OWNER)).thenReturn(Optional.of(task(TaskStatus.QUEUED)));
    Mockito.doThrow(new TaskQueue.UnavailableException("broker down", new RuntimeException()))
        .when(queue)
        .publish("task-1");

    ResponseStatusException error =
        assertThrows(ResponseStatusException.class, () -> service.retry("task-1", OWNER));

    org.junit.jupiter.api.Assertions.assertEquals(
        HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
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
  void ignoresDeliveryForTaskDeletedBeforeProcessing() {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.empty());

    assertDoesNotThrow(() -> service.process("task-1", 1));

    verify(tasks).find("task-1");
    verify(tasks, never()).claimForProcessing(any(), any(), any());
  }

  /** 重复投递的兜底：只有把任务从 QUEUED 原子改成 PROCESSING 成功的那次投递才会真正执行。 */
  @Test
  void ignoresDuplicateDeliveryWhenTaskIsAlreadyClaimed() {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.PROCESSING)));
    when(tasks.claimForProcessing(eq("task-1"), any(), any())).thenReturn(false);

    service.process("task-1", 1);

    verify(tasks).claimForProcessing(eq("task-1"), any(), any());
    verify(tasks, never()).update(any(), any(), any(), anyInt(), any());
  }

  /** 重复投递（第 1 次尝试）不会去动仍为 PROCESSING 的任务，避免把别的执行者的活抢过来。 */
  @Test
  void doesNotReleaseProcessingTaskOnFirstDelivery() {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.PROCESSING)));
    when(tasks.claimForProcessing(eq("task-1"), any(), any())).thenReturn(false);

    service.process("task-1", 1);

    verify(tasks, never()).releaseProcessing(any(), any(), any());
  }

  /** 重试投递遇到上次被故障打断、仍停在 PROCESSING 的任务时，先放回 QUEUED 才能重新领取。 */
  @Test
  void releasesTaskLeftProcessingBeforeRetryDelivery() {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.PROCESSING)));
    when(tasks.releaseProcessing(eq("task-1"), any(), any())).thenReturn(true);
    when(tasks.claimForProcessing(eq("task-1"), any(), any())).thenReturn(true);

    service.process("task-1", 2);

    verify(tasks).releaseProcessing(eq("task-1"), any(), any());
    verify(tasks).claimForProcessing(eq("task-1"), any(), any());
  }

  /** 多实例：任务仍挂在别的实例未过期的租约上时，重试投递不能把它抢过来执行。 */
  @Test
  void doesNotStealTaskHeldByAnotherInstance() {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.PROCESSING)));
    when(tasks.releaseProcessing(eq("task-1"), any(), any())).thenReturn(false);

    service.process("task-1", 2);

    verify(tasks).releaseProcessing(eq("task-1"), any(), any());
    verify(tasks, never()).claimForProcessing(any(), any(), any());
    verify(tasks, never()).update(any(), any(), any(), anyInt(), any());
  }

  /** 重试耗尽后任务判为失败，原因写进错误信息供用户决定是否人工重投。 */
  @Test
  void marksTaskFailedWhenRetriesAreExhausted() {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.QUEUED)));

    service.markRetryExhausted("task-1", 4, "whisper_unavailable", "Whisper 服务不可达");

    org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(tasks).update(eq("task-1"), eq(TaskStatus.FAILED), any(), eq(0), message.capture());
    org.junit.jupiter.api.Assertions.assertTrue(
        message.getValue().contains("自动重试 4 次")
            && message.getValue().contains("whisper_unavailable"),
        "错误信息应写明重试次数与原因，实际：" + message.getValue());
  }

  /** 已经成功或已取消的任务不会被迟到的重试耗尽事件改写状态。 */
  @Test
  void leavesFinishedTaskUntouchedWhenRetriesAreExhausted() {
    TaskService service = service();
    when(tasks.find("task-1")).thenReturn(Optional.of(task(TaskStatus.COMPLETED)));

    service.markRetryExhausted("task-1", 4, "whisper_unavailable", "Whisper 服务不可达");

    verify(tasks, never()).update(any(), any(), any(), anyInt(), any());
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
  void savesTaskWithRetryHintWhenQueueIsUnavailable() throws Exception {
    Path storage = Files.createTempDirectory("task-service-queue-test");
    TaskService service = service(storage);
    try {
      Mockito.doThrow(new TaskQueue.UnavailableException("broker down", new RuntimeException()))
          .when(queue)
          .publish(any());

      VideoTask imported =
          service.importVideo(
              new MockMultipartFile("file", "queued.mp4", "video/mp4", new byte[] {1, 2, 3}),
              OWNER);

      org.junit.jupiter.api.Assertions.assertEquals(TaskStatus.QUEUED, imported.status());
      org.junit.jupiter.api.Assertions.assertTrue(imported.errorMessage().contains("消息队列暂不可用"));
      verify(tasks, times(1)).save(any(), eq(OWNER));
      verify(tasks)
          .update(
              any(), org.mockito.ArgumentMatchers.eq(TaskStatus.QUEUED), any(), anyInt(), any());
    } finally {
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
    WorkbenchMetrics metrics =
        new WorkbenchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    return new TaskService(
        tasks,
        new ObjectMapper(),
        llm,
        new ResultValidator(),
        metrics,
        storage.toString(),
        "ffmpeg",
        "http://127.0.0.1:8090",
        "test-token-test-token-test-token-test-token",
        "turbo",
        "",
        1024,
        1,
        60000,
        queue,
        new TaskLease(tasks, queue, metrics, "test-instance", 90),
        DownstreamResilience.withDefaults());
  }

  private VideoTask task(TaskStatus status) {
    return task("task-1", status);
  }

  private VideoTask task(String id, TaskStatus status) {
    Instant now = Instant.now();
    return new VideoTask(
        id, "video.mp4", null, "video.mp4", 1, status, TaskStage.IMPORT, 0, null, now, now);
  }
}
