package ai.toni.videoworkbench;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.dao.DataAccessResourceFailureException;

/** 消费者的确认语义与失败分流：瞬时故障退避重试、超限进死信、非瞬时故障不浪费重试额度。 */
class TaskQueueConsumerTest {
  private static final long DELIVERY_TAG = 7L;

  private final TaskService service = Mockito.mock(TaskService.class);
  private final TaskQueue queue = Mockito.mock(TaskQueue.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WorkbenchMetrics metrics = new WorkbenchMetrics(registry);
  private final RabbitListenerEndpointRegistry listeners = new RabbitListenerEndpointRegistry();
  private final Channel channel = Mockito.mock(Channel.class);

  @Test
  void acknowledgesMessageAfterTaskIsProcessed() throws Exception {
    consumer().handle(message("task-1", 1), channel, DELIVERY_TAG);

    verify(service).process("task-1", 1);
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    verify(queue, never()).publishRetry(anyString(), anyInt(), anyLong());
  }

  /** 首次失败后等初始延迟（默认 5s）再做第 2 次尝试，等待由重试队列的消息 TTL 承载。 */
  @Test
  void schedulesRetryWithInitialDelayWhenTransientFailureHappens() throws Exception {
    Mockito.doThrow(new TransientFailure(TransientFailure.WHISPER_UNAVAILABLE, "Whisper 服务不可达"))
        .when(service)
        .process("task-1", 1);

    consumer().handle(message("task-1", 1), channel, DELIVERY_TAG);

    verify(queue).publishRetry("task-1", 2, 5_000L);
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0,
        registry.get("workbench.queue.retries").tag("attempt", "2").counter().count(),
        "应记录到「即将进行第 2 次尝试」的退避重试");
  }

  /** 退避间隔随失败次数递增：第 3 次失败后等 20s（5s × 2²），而不是固定间隔。 */
  @Test
  void increasesBackoffDelayWithEachFailedAttempt() throws Exception {
    Mockito.doThrow(new TransientFailure(TransientFailure.WHISPER_UNAVAILABLE, "Whisper 服务不可达"))
        .when(service)
        .process("task-1", 3);

    consumer().handle(message("task-1", 3), channel, DELIVERY_TAG);

    verify(queue).publishRetry("task-1", 4, 20_000L);
    verify(service).process("task-1", 3);
  }

  /** 数据库不可达同样是瞬时故障，但不能在 TaskService 里改状态（写不进去），由消费者安排重试。 */
  @Test
  void schedulesRetryWhenDatabaseIsUnreachable() throws Exception {
    Mockito.doThrow(new DataAccessResourceFailureException("connection refused"))
        .when(service)
        .process("task-1", 1);

    consumer().handle(message("task-1", 1), channel, DELIVERY_TAG);

    verify(queue).publishRetry("task-1", 2, 5_000L);
    verify(channel).basicAck(DELIVERY_TAG, false);
  }

  /** 到上限后不再重投：任务标为失败、消息进死信队列留档，用户可在界面上人工重投。 */
  @Test
  void sendsTaskToDeadLetterQueueAfterMaxAttempts() throws Exception {
    Mockito.doThrow(new TransientFailure(TransientFailure.WHISPER_UNAVAILABLE, "Whisper 服务不可达"))
        .when(service)
        .process("task-1", 4);

    consumer().handle(message("task-1", 4), channel, DELIVERY_TAG);

    verify(service)
        .markRetryExhausted("task-1", 4, TransientFailure.WHISPER_UNAVAILABLE, "Whisper 服务不可达");
    verify(queue).publishDeadLetter("task-1", TransientFailure.WHISPER_UNAVAILABLE);
    verify(queue, never()).publishRetry(anyString(), anyInt(), anyLong());
    verify(channel).basicAck(DELIVERY_TAG, false);
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, registry.get("workbench.queue.dead_letters").counter().count());
  }

  /** 代码缺陷之类的异常等多久都一样失败，直接进死信队列，不占用重试额度。 */
  @Test
  void deadLettersUnexpectedFailureWithoutSpendingRetries() throws Exception {
    Mockito.doThrow(new IllegalStateException("bug")).when(service).process("task-1", 1);

    consumer().handle(message("task-1", 1), channel, DELIVERY_TAG);

    verify(queue).publishDeadLetter("task-1", "unexpected");
    verify(queue, never()).publishRetry(anyString(), anyInt(), anyLong());
    verify(channel).basicAck(DELIVERY_TAG, false);
  }

  @Test
  void returnsMessageToQueueWhenRetryCannotBePublished() throws Exception {
    Mockito.doThrow(new TransientFailure(TransientFailure.WHISPER_UNAVAILABLE, "down"))
        .when(service)
        .process("task-1", 1);
    Mockito.doThrow(new TaskQueue.UnavailableException("broker down", new RuntimeException()))
        .when(queue)
        .publishRetry(eq("task-1"), anyInt(), anyLong());

    consumer().handle(message("task-1", 1), channel, DELIVERY_TAG);

    verify(channel).basicNack(DELIVERY_TAG, false, true);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  /** 死信队列也投不出去时，拒绝消息（requeue=false）让主队列自己的 DLX 兜底，消息不会凭空消失。 */
  @Test
  void rejectsMessageWhenDeadLetterCannotBePublished() throws Exception {
    Mockito.doThrow(new IllegalStateException("bug")).when(service).process("task-1", 1);
    Mockito.doThrow(new TaskQueue.UnavailableException("broker down", new RuntimeException()))
        .when(queue)
        .publishDeadLetter(anyString(), anyString());

    consumer().handle(message("task-1", 1), channel, DELIVERY_TAG);

    verify(channel).basicNack(DELIVERY_TAG, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void recordsQueueWaitFromMessageTimestamp() throws Exception {
    Message message = message("task-1", 1);
    message.getMessageProperties().setTimestamp(new Date(System.currentTimeMillis() - 1_500));

    consumer().handle(message, channel, DELIVERY_TAG);

    double waited =
        registry
            .get("workbench.queue.wait")
            .timer()
            .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
    org.junit.jupiter.api.Assertions.assertTrue(waited >= 1_000, "等待时长应至少 1 秒，实际 " + waited);
  }

  /** 容器不存在时只记错误日志，不能让启动恢复流程崩在消费者启动这一步。 */
  @Test
  void survivesStartingWithoutRegisteredListener() {
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> consumer().start());
  }

  private TaskQueueConsumer consumer() {
    return new TaskQueueConsumer(service, queue, metrics, listeners, 4, 5_000L, 2);
  }

  private Message message(String taskId, int attempt) {
    MessageProperties properties = new MessageProperties();
    properties.setHeader(TaskQueueConsumer.ATTEMPT_HEADER, String.valueOf(attempt));
    return new Message(taskId.getBytes(StandardCharsets.UTF_8), properties);
  }
}
