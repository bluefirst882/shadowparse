package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 队列外置后的投递语义：消息持久化、broker 拒收可观测、队列深度读不到时不能伪装成 0。 */
class RabbitTaskQueueTest {
  private static final String QUEUE = "workbench.tasks";

  private final RabbitTemplate template = Mockito.mock(RabbitTemplate.class);
  private final AmqpAdmin admin = Mockito.mock(AmqpAdmin.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WorkbenchMetrics metrics = new WorkbenchMetrics(registry);

  @Test
  void publishesTaskIdAsPersistentMessage() {
    RabbitTaskQueue queue = queue();

    queue.publish("task-1");

    ArgumentCaptor<MessagePostProcessor> processor =
        ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(template)
        .convertAndSend(
            eq(""), eq(QUEUE), eq("task-1"), processor.capture(), any(CorrelationData.class));
    Message message =
        processor
            .getValue()
            .postProcessMessage(
                new Message("task-1".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
    assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
  }

  @Test
  void reportsUnavailableWhenBrokerCannotBeReached() {
    Mockito.doThrow(new AmqpException("connection refused"))
        .when(template)
        .convertAndSend(
            anyString(),
            anyString(),
            any(),
            any(MessagePostProcessor.class),
            any(CorrelationData.class));
    RabbitTaskQueue queue = queue();

    assertThrows(TaskQueue.UnavailableException.class, () -> queue.publish("task-1"));
  }

  @Test
  void countsFailureWhenBrokerRejectsPublishedMessage() {
    RabbitTaskQueue queue = queue();

    queue.publish("task-1");
    ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
    verify(template)
        .convertAndSend(
            eq(""),
            eq(QUEUE),
            eq("task-1"),
            any(MessagePostProcessor.class),
            correlation.capture());
    correlation.getValue().getFuture().complete(new CorrelationData.Confirm(false, "queue full"));

    assertEquals(1.0, registry.get("workbench.queue.publish_failures").counter().count());
  }

  @Test
  void reportsBrokerSideQueueDepth() {
    Properties properties = new Properties();
    properties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 3);
    Mockito.when(admin.getQueueProperties(QUEUE)).thenReturn(properties);

    queue();

    assertEquals(3.0, registry.get("workbench.queue.depth").gauge().value());
  }

  @Test
  void reportsNegativeDepthWhenBrokerIsUnreachable() {
    Mockito.when(admin.getQueueProperties(QUEUE)).thenThrow(new AmqpException("down"));

    queue();

    assertEquals(-1.0, registry.get("workbench.queue.depth").gauge().value());
  }

  private RabbitTaskQueue queue() {
    return new RabbitTaskQueue(template, admin, metrics, QUEUE);
  }
}
