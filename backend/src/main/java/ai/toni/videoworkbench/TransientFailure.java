package ai.toni.videoworkbench;

/**
 * 可重试的基础设施故障（瞬时故障）。
 *
 * <p>判定标准是「等一会儿重来可能就好了」，而不是「重来多少次都一样」：Whisper 服务不可达 / 超时 / 返回 5xx、 数据库短暂不可用都算瞬时故障；媒体损坏、FFmpeg 退出码非
 * 0、输入不合法则不是——这类问题每次重试都会以同样方式失败， 抛成瞬时故障只会让失败被拖延到重试上限。
 *
 * <p>抛出方（{@link TaskService}）负责把任务放回 {@code QUEUED}，由消费者（{@link TaskQueueConsumer}）决定
 * 退避重试还是进死信队列；两者分开是因为「任务状态怎么改」属于业务，「消息投到哪个队列」属于传输。
 */
class TransientFailure extends RuntimeException {
  /** Whisper 服务不可达、超时或返回 5xx。 */
  static final String WHISPER_UNAVAILABLE = "whisper_unavailable";

  /** 数据库不可达或连接中断。 */
  static final String DATABASE_UNAVAILABLE = "database_unavailable";

  /** LLM 调用被熔断器或并发隔板拒绝（请求没发出去）。 */
  static final String LLM_UNAVAILABLE = "llm_unavailable";

  private final String reason;

  TransientFailure(String reason, String message) {
    super(message);
    this.reason = reason;
  }

  TransientFailure(String reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /** 低基数分类，用于指标标签与日志，取值见本类常量。 */
  String reason() {
    return reason;
  }
}
