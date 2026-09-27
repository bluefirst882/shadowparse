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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
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
 *
 * <p>失败按三类处理，都不再原地 requeue 打转：
 *
 * <ul>
 *   <li><b>瞬时故障</b>（{@link TransientFailure}：Whisper 不可达 / 超时 / 5xx；数据库不可达）：投递到重试队列， 带 TTL
 *       等一段退避时间后回到主队列，作为下一次尝试；
 *   <li><b>超过重试上限</b>：消息落死信队列留档，任务在库里标为 FAILED，用户在界面上点「重试」即可人工重投；
 *   <li><b>其它异常</b>（代码缺陷、消息本身有问题）：等多久都一样失败，直接退到死信队列，不浪费重试次数。
 * </ul>
 */
@Component
class TaskQueueConsumer {
  static final String LISTENER_ID = "taskQueueListener";

  /** 重试消息头：本次是第几次尝试（首次投递没有这个头，按第 1 次算）。 */
  static final String ATTEMPT_HEADER = "x-attempt";

  /** 单次重试延迟的上限：即使把 max-attempts 配得很大，也不会出现「几天后才重试」。 */
  private static final long MAX_RETRY_DELAY_MILLIS = Duration.ofHours(6).toMillis();

  private static final Logger log = LoggerFactory.getLogger(TaskQueueConsumer.class);

  private final TaskService service;
  private final TaskQueue queue;
  private final WorkbenchMetrics metrics;
  private final RabbitListenerEndpointRegistry registry;
  private final int maxAttempts;
  private final long retryInitialDelayMillis;
  private final double retryMultiplier;

  TaskQueueConsumer(
      TaskService service,
      TaskQueue queue,
      WorkbenchMetrics metrics,
      RabbitListenerEndpointRegistry registry,
      @Value("${workbench.queue.max-attempts:4}") int maxAttempts,
      @Value("${workbench.queue.retry-initial-delay-millis:5000}") long retryInitialDelayMillis,
      @Value("${workbench.queue.retry-multiplier:2}") double retryMultiplier) {
    this.service = service;
    this.queue = queue;
    this.metrics = metrics;
    this.registry = registry;
    this.maxAttempts = maxAttempts;
    this.retryInitialDelayMillis = retryInitialDelayMillis;
    this.retryMultiplier = retryMultiplier;
  }

  @RabbitListener(id = LISTENER_ID, queues = "${workbench.queue.task-queue}", autoStartup = "false")
  void handle(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
      throws IOException {
    String taskId = new String(message.getBody(), StandardCharsets.UTF_8);
    int attempt = attemptOf(message);
    recordWait(message);
    try {
      service.process(taskId, attempt);
      channel.basicAck(deliveryTag, false);
    } catch (TransientFailure ex) {
      retryLater(taskId, attempt, ex.reason(), ex.getMessage(), channel, deliveryTag);
    } catch (DataAccessException ex) {
      // 数据库不可达属于瞬时故障，但此时连「把任务改回 QUEUED」都写不进去，只能靠重试投递再来一次。
      retryLater(
          taskId,
          attempt,
          TransientFailure.DATABASE_UNAVAILABLE,
          ex.getMessage(),
          channel,
          deliveryTag);
    } catch (Exception ex) {
      // 非瞬时异常不能无限重投（重投只是反复撞同一堵墙），直接留档到死信队列等人看。
      log.error("任务处理出现非瞬时异常，消息转入死信队列：taskId={}", taskId, ex);
      deadLetter(taskId, "unexpected", ex.getMessage(), channel, deliveryTag);
    }
  }

  /** 瞬时故障后安排下一次尝试；已经到上限则标记任务失败并进死信队列。 */
  private void retryLater(
      String taskId, int attempt, String reason, String detail, Channel channel, long deliveryTag)
      throws IOException {
    if (attempt >= maxAttempts) {
      log.error(
          "任务已达重试上限 {} 次，转入死信队列：taskId={} reason={} detail={}",
          maxAttempts,
          taskId,
          reason,
          detail);
      markExhausted(taskId, attempt, reason, detail);
      deadLetter(taskId, reason, detail, channel, deliveryTag);
      return;
    }
    long delay = delayAfter(attempt);
    try {
      queue.publishRetry(taskId, attempt + 1, delay);
    } catch (RuntimeException ex) {
      // 重试消息投不出去也不能让这条消息消失：退回主队列立即重投（没有退避，但至少不丢）。
      log.error("重试消息投递失败，消息退回主队列：taskId={} delayMillis={}", taskId, delay, ex);
      channel.basicNack(deliveryTag, false, true);
      return;
    }
    metrics.recordQueueRetry(reason, attempt + 1);
    log.warn(
        "任务处理遇到瞬时故障，{} ms 后进行第 {} 次尝试：taskId={} reason={} detail={}",
        delay,
        attempt + 1,
        taskId,
        reason,
        detail);
    channel.basicAck(deliveryTag, false);
  }

  /** 投递到死信队列并确认原消息；死信队列也投不出去时直接拒绝，交由主队列自身的 DLX 兜底。 */
  private void deadLetter(
      String taskId, String reason, String detail, Channel channel, long deliveryTag)
      throws IOException {
    try {
      queue.publishDeadLetter(taskId, reason);
    } catch (RuntimeException ex) {
      log.error("死信消息投递失败，改为拒绝消息交由主队列 DLX 处理：taskId={}", taskId, ex);
      channel.basicNack(deliveryTag, false, false);
      return;
    }
    metrics.recordQueueDeadLetter(reason);
    channel.basicAck(deliveryTag, false);
  }

  /** 标记重试耗尽只在数据库可达时有意义，失败也不能挡住消息进死信队列。 */
  private void markExhausted(String taskId, int attempt, String reason, String detail) {
    try {
      service.markRetryExhausted(taskId, attempt, reason, detail);
    } catch (RuntimeException ex) {
      log.error("标记任务重试耗尽失败（消息仍会进死信队列）：taskId={}", taskId, ex);
    }
  }

  /**
   * 第 {@code attempt} 次尝试失败后要等多久才做下一次尝试：初始延迟乘以倍率的指数退避（默认 5s → 10s → 20s）。
   *
   * <p>退避时间用 broker 的消息 TTL 承载，进程重启也不会把等待中的重试丢掉。
   */
  private long delayAfter(int attempt) {
    double delay = retryInitialDelayMillis * Math.pow(retryMultiplier, Math.max(0, attempt - 1));
    return Math.min((long) delay, MAX_RETRY_DELAY_MILLIS);
  }

  /** 记录任务在队列中的等待时长：从投递时刻（broker 侧排队时间也算在内）到真正开始处理。 */
  private void recordWait(Message message) {
    Date sentAt = message.getMessageProperties().getTimestamp();
    if (sentAt == null) return;
    metrics.recordQueueWait(Duration.between(sentAt.toInstant(), Instant.now()));
  }

  private static int attemptOf(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(ATTEMPT_HEADER);
    if (value == null) return 1;
    try {
      return Math.max(1, Integer.parseInt(value.toString().trim()));
    } catch (NumberFormatException ex) {
      return 1;
    }
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
