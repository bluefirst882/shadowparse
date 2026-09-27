package ai.toni.videoworkbench;

/**
 * 待处理任务的投递队列。
 *
 * <p>队列外置到 broker（RabbitMQ）之后，进程重启不再丢任务：任务是先落库为 {@code QUEUED} 再投递消息， 消息持久化且消费者手动
 * ack，因此「消息重复投递」和「消息丢失」都由数据库的条件更新兜底 （见 {@link TaskRepository#claimForProcessing}）。
 *
 * <p>业务侧只依赖这个接口，不为 RabbitMQ 的 API 所绑定；单元测试注入假实现即可。
 */
interface TaskQueue {
  /**
   * 投递一个待处理任务。
   *
   * @throws UnavailableException broker 不可用，消息确定没有投递出去
   */
  void publish(String taskId);

  /** 队列不可用。调用方据此把任务留在 {@code QUEUED} 并提示用户稍后重试，而不是假装已经入队。 */
  class UnavailableException extends RuntimeException {
    UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
