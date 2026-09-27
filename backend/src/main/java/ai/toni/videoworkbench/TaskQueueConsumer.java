package ai.toni.videoworkbench;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 任务队列消费者。
 *
 * <p>消费并发固定为 1、prefetch 为 1：本地 FFmpeg / Whisper 是稀缺资源，串行处理避免 GPU 与磁盘互相抢占； 来不及处理的消息留在 broker
 * 里排队，这就是队列外置带来的背压，而不是在进程里堆任务。
 *
 * <p>消费者刻意不自动启动（{@code autoStartup = "false"}）：进程崩溃后 broker 会重投那条未确认的消息， 而此刻任务在库里还是 {@code
 * PROCESSING}；必须先跑完启动恢复（把残留的 {@code PROCESSING} 改回 {@code QUEUED}）再开始消费， 否则重投的消息会因为领不到任务而被白白确认掉。
 */
@Component
class TaskQueueConsumer {
  static final String LISTENER_ID = "taskQueueListener";

  private static final Logger log = LoggerFactory.getLogger(TaskQueueConsumer.class);

  private final TaskService service;
  private final WorkbenchMetrics metrics;
  private final RabbitListenerEndpointRegistry registry;

  TaskQueueConsumer(
      TaskService service, WorkbenchMetrics metrics, RabbitListenerEndpointRegistry registry) {
    this.service = service;
    this.metrics = metrics;
    this.registry = registry;
  }

  @RabbitListener(id = LISTENER_ID, queues = "${workbench.queue.task-queue}", autoStartup = "false")
  void handle(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
      throws IOException {
    String taskId = new String(message.getBody(), StandardCharsets.UTF_8);
    recordWait(message);
    try {
      service.process(taskId);
      channel.basicAck(deliveryTag, false);
    } catch (Exception ex) {
      // process 内部已经把业务失败写成 FAILED 并正常返回，能抛到这里的基本是基础设施故障（数据库不可达等）。
      // 这类消息不能丢，退回队列等待重投（P2-2 会把它换成带退避的延迟重投）。
      log.warn("任务处理出现基础设施异常，消息退回队列：taskId={}", taskId, ex);
      channel.basicNack(deliveryTag, false, true);
    }
  }

  /** 记录任务在队列中的等待时长：从投递时刻（broker 侧排队时间也算在内）到真正开始处理。 */
  private void recordWait(Message message) {
    Date sentAt = message.getMessageProperties().getTimestamp();
    if (sentAt == null) return;
    metrics.recordQueueWait(Duration.between(sentAt.toInstant(), Instant.now()));
  }

  /** 启动恢复完成后才允许开始消费，避免与恢复流程抢同一个任务。 */
  void start() {
    MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
    if (container == null) {
      log.error("未找到任务队列消费者容器 {}，任务不会被消费", LISTENER_ID);
      return;
    }
    try {
      container.start();
      log.info("任务队列消费者已启动：{}", LISTENER_ID);
    } catch (RuntimeException ex) {
      // broker 暂时不可用时只告警：HTTP 接口仍可用，任务留在队列里，broker 恢复后由容器自行重连。
      log.warn("任务队列消费者启动失败，等待 broker 恢复后重连：{}", ex.getMessage());
    }
  }
}
