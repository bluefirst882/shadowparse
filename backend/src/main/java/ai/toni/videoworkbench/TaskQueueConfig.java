package ai.toni.videoworkbench;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 队列拓扑：主队列 + 重试队列 + 死信队列，三者都是持久化队列。
 *
 * <pre>
 *                     ┌─────────────── 消费失败（超过重试上限）→ dead ──┐
 *   publish ──────► workbench.tasks                                     │
 *                     │  （重试消息 TTL 到期）                  workbench.tasks.dead
 *                     └── workbench.tasks.retry ◄── retry ──────────────┘
 * </pre>
 *
 * <p>路由约定：所有死信都发到 {@link #DEAD_LETTER_EXCHANGE}，用 routing key 区分去向——{@code dead} 进死信队列（人工重投），
 * {@code tasks} 回到主队列（重试等待结束），{@code retry} 进重试队列（等待退避时间）。
 *
 * <p>重试延迟由消息自身的 TTL 承载（见 {@link RabbitTaskQueue#publishRetry}）：重试队列是同一批消息的等待区，
 * 到点就回到主队列重新被消费，不需要在进程里 sleep 或维护定时器。
 *
 * <p>注意 per-message TTL 只在队头消息上被判定：若队头是 20s 的消息、后面才是 10s 的消息，后者会等到 20s。 本项目默认退避只有 5s / 10s / 20s
 * 三级，最坏多等 20s，可以接受；要严格按各自 TTL 到期就需要按延迟级别拆多个队列。
 */
@Configuration
class TaskQueueConfig {
  /** 死信交换机名。队列与消息的 DLX 都指向它，再由 routing key 分流。 */
  static final String DEAD_LETTER_EXCHANGE = "workbench.tasks.dlx";

  /** 超过重试上限：消息落死信队列，任务状态为 FAILED，由人工确认后重投。 */
  static final String ROUTING_DEAD = "dead";

  /** 重试等待结束：消息回到主队列重新被消费。 */
  static final String ROUTING_TASK = "tasks";

  /** 需要延迟重试：消息先进重试队列等退避时间。 */
  static final String ROUTING_RETRY = "retry";

  @Bean
  DirectExchange taskDeadLetterExchange() {
    return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
  }

  /** 主队列：消费失败且不重试的消息按 {@link #ROUTING_DEAD} 进死信队列。 */
  @Bean
  Queue taskQueue(@Value("${workbench.queue.task-queue}") String queueName) {
    return QueueBuilder.durable(queueName)
        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
        .deadLetterRoutingKey(ROUTING_DEAD)
        .build();
  }

  /** 重试队列：消息带 per-message TTL，到期后按 {@link #ROUTING_TASK} 回到主队列。 */
  @Bean
  Queue taskRetryQueue(@Value("${workbench.queue.task-queue}") String queueName) {
    return QueueBuilder.durable(queueName + ".retry")
        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
        .deadLetterRoutingKey(ROUTING_TASK)
        .build();
  }

  @Bean
  Queue taskDeadLetterQueue(@Value("${workbench.queue.dead-letter-queue}") String queueName) {
    return QueueBuilder.durable(queueName).build();
  }

  @Bean
  Binding taskQueueDeadLetterBinding(
      Queue taskQueue, Queue taskDeadLetterQueue, DirectExchange taskDeadLetterExchange) {
    return BindingBuilder.bind(taskQueue)
        .to(taskDeadLetterExchange)
        .with(TaskQueueConfig.ROUTING_TASK);
  }

  @Bean
  Binding taskRetryBinding(Queue taskRetryQueue, DirectExchange taskDeadLetterExchange) {
    return BindingBuilder.bind(taskRetryQueue)
        .to(taskDeadLetterExchange)
        .with(TaskQueueConfig.ROUTING_RETRY);
  }

  @Bean
  Binding taskDeadLetterBinding(Queue taskDeadLetterQueue, DirectExchange taskDeadLetterExchange) {
    return BindingBuilder.bind(taskDeadLetterQueue)
        .to(taskDeadLetterExchange)
        .with(TaskQueueConfig.ROUTING_DEAD);
  }
}
