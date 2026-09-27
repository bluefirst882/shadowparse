package ai.toni.videoworkbench;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 实现的任务队列。
 *
 * <p>消息持久化 + 手动 ack：进程在处理中被杀掉时消息仍是未确认状态，broker 会在消费者重新连接后再次投递；
 * 重复投递不会导致同一任务被处理两次，因为真正执行前要先在数据库里原子领取（{@link TaskRepository#claimForProcessing}）。
 *
 * <p>队列深度指标在这里绑定 broker 侧的真实积压量，而不是进程内的某个计数器：消费端只有 1 个并发、prefetch=1， 来不及处理的消息留在 broker，指标就能如实反映积压。
 */
@Component
class RabbitTaskQueue implements TaskQueue {
  private static final Logger log = LoggerFactory.getLogger(RabbitTaskQueue.class);

  private final RabbitTemplate template;
  private final AmqpAdmin admin;
  private final WorkbenchMetrics metrics;
  private final String queueName;
  private volatile boolean depthAvailable = true;

  RabbitTaskQueue(
      RabbitTemplate template,
      AmqpAdmin admin,
      WorkbenchMetrics metrics,
      @Value("${workbench.queue.task-queue}") String queueName) {
    this.template = template;
    this.admin = admin;
    this.metrics = metrics;
    this.queueName = queueName;
    metrics.bindQueueDepth(this::depth);
  }

  @Override
  public void publish(String taskId) {
    send("", queueName, taskId, Map.of(), null);
  }

  @Override
  public void publishRetry(String taskId, int attempt, long delayMillis) {
    // 重试消息先落在重试队列上等 per-message TTL 到期（不占线程 sleep），到期后由该队列自己的 DLX
    // 按 ROUTING_TASK 送回主队列，作为一次新的投递被消费。
    send(
        TaskQueueConfig.DEAD_LETTER_EXCHANGE,
        TaskQueueConfig.ROUTING_RETRY,
        taskId,
        Map.of(TaskQueueConsumer.ATTEMPT_HEADER, attempt),
        delayMillis);
  }

  @Override
  public void publishDeadLetter(String taskId, String reason) {
    send(
        TaskQueueConfig.DEAD_LETTER_EXCHANGE,
        TaskQueueConfig.ROUTING_DEAD,
        taskId,
        Map.of("x-reason", reason),
        null);
  }

  /**
   * 统一投递：持久化 + 时间戳 + 可选 TTL 与自定义头，并统一做发布确认打点。
   *
   * @param ttlMillis 非空时设置 per-message TTL（毫秒），用于重试队列的退避等待
   */
  private void send(
      String exchange,
      String routingKey,
      String taskId,
      Map<String, Object> headers,
      Long ttlMillis) {
    CorrelationData correlation = new CorrelationData(taskId);
    try {
      template.convertAndSend(
          exchange,
          routingKey,
          taskId,
          message -> {
            MessageProperties properties = message.getMessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            // 打上投递时刻：消费端据此计算任务在队列里的真实等待时长（含 broker 侧排队）。
            properties.setTimestamp(new Date());
            headers.forEach(properties::setHeader);
            if (ttlMillis != null) properties.setExpiration(String.valueOf(ttlMillis));
            return message;
          },
          correlation);
    } catch (AmqpException ex) {
      throw new UnavailableException("消息队列不可用：" + ex.getMessage(), ex);
    }
    // 同步不抛异常只说明请求发出去了，broker 仍可能拒收（磁盘告警、队列被删）。
    // 这类失败是异步回报的，必须打点：否则任务停在 QUEUED 不执行，用户却以为已经入队。
    correlation
        .getFuture()
        .whenComplete(
            (confirm, error) -> {
              if (error == null && confirm != null && confirm.isAck()) return;
              metrics.recordQueuePublishFailure();
              log.error(
                  "任务消息未被 broker 确认：taskId={}，原因={}",
                  taskId,
                  error != null ? error.getMessage() : confirm.getReason());
            });
  }

  /** broker 侧主队列的积压消息数；broker 不可达或队列尚未声明时返回 {@code -1}，避免把「读不到」伪装成「没有积压」。 */
  private long depth() {
    try {
      Properties properties = admin.getQueueProperties(queueName);
      Object count = properties == null ? null : properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
      depthAvailable = true;
      return count instanceof Number number ? number.longValue() : -1L;
    } catch (AmqpException ex) {
      // 指标每次抓取都会走这里，只在状态翻转时告警，避免刷日志。
      if (depthAvailable) log.warn("队列深度不可用，指标返回 -1：{}", ex.getMessage());
      depthAvailable = false;
      return -1L;
    }
  }
}
