package ai.toni.videoworkbench;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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

/** 消费者的确认语义：处理完才 ack，基础设施故障时退回队列而不是丢消息。 */
class TaskQueueConsumerTest {
  private static final long DELIVERY_TAG = 7L;

  private final TaskService service = Mockito.mock(TaskService.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WorkbenchMetrics metrics = new WorkbenchMetrics(registry);
  private final RabbitListenerEndpointRegistry listeners = new RabbitListenerEndpointRegistry();
  private final Channel channel = Mockito.mock(Channel.class);

  @Test
  void acknowledgesMessageAfterTaskIsProcessed() throws Exception {
    consumer().handle(message("task-1"), channel, DELIVERY_TAG);

    verify(service).process("task-1");
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void returnsMessageToQueueWhenInfrastructureFails() throws Exception {
    Mockito.doThrow(new IllegalStateException("database down")).when(service).process("task-1");

    consumer().handle(message("task-1"), channel, DELIVERY_TAG);

    verify(channel).basicNack(DELIVERY_TAG, false, true);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void recordsQueueWaitFromMessageTimestamp() throws Exception {
    Message message = message("task-1");
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
    return new TaskQueueConsumer(service, metrics, listeners);
  }

  private Message message(String taskId) {
    return new Message(taskId.getBytes(StandardCharsets.UTF_8), new MessageProperties());
  }
}
