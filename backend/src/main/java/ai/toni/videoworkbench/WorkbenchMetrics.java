package ai.toni.videoworkbench;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 指标埋点集中入口。
 *
 * <p>业务代码只调用这里的方法，不直接依赖 {@link MeterRegistry}：指标名与标签在此统一约定， 避免任务 id、文件名等高基数维度进入标签。
 *
 * <p>队列相关指标通过 {@link Supplier} 绑定数据源，业务侧不感知底层队列类型：当前绑定进程内 有界队列的 {@code size()}；P2-1 换成 RabbitMQ
 * 时只需替换绑定源与调用点，指标名与语义保持不变。
 */
@Component
class WorkbenchMetrics {
  // Micrometer 名称用点号分隔；Prometheus 导出时转为下划线，见 README「可观测性」。
  static final String TRANSCRIPTION_DURATION = "workbench.transcription.duration";
  static final String LLM_REQUESTS = "workbench.llm.requests";
  static final String LLM_RETRIES = "workbench.llm.retries";
  static final String LLM_VALIDATION_FAILURES = "workbench.llm.validation_failures";
  static final String LLM_TOKENS = "workbench.llm.tokens";
  static final String QUEUE_DEPTH = "workbench.queue.depth";
  static final String QUEUE_WAIT = "workbench.queue.wait";

  private final MeterRegistry registry;

  WorkbenchMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** 一次 Whisper 转写（含片段持久化）完成后记录耗时，{@code success} 区分成功与失败。 */
  void recordTranscription(Duration duration, boolean success) {
    Timer.builder(TRANSCRIPTION_DURATION)
        .description("Whisper 转写阶段耗时")
        .tag("outcome", success ? "success" : "failure")
        .publishPercentileHistogram()
        .register(registry)
        .record(duration);
  }

  /** 每次 LLM HTTP 调用按结果打点，{@code operation} 取 summarize / translate。 */
  void recordLlmCall(String operation, boolean success) {
    registry
        .counter(LLM_REQUESTS, "operation", operation, "outcome", success ? "success" : "failure")
        .increment();
  }

  /** 摘要生成在单次任务内的重试次数（首次之外的每次尝试）。 */
  void recordLlmRetry(String operation) {
    registry.counter(LLM_RETRIES, "operation", operation).increment();
  }

  /**
   * 结果校验拦下一次未通过的模型输出，{@code reason} 取校验失败分类（如 quote_mismatch）。
   *
   * <p>用于观测「反幻觉闸门到底拦住了几次」，标签只取有限枚举值，避免高基数。
   */
  void recordLlmValidationFailure(String reason) {
    registry.counter(LLM_VALIDATION_FAILURES, "reason", reason).increment();
  }

  /** 从 LLM 响应 usage 字段累加 token 用量，{@code type} 取 prompt / completion。 */
  void recordLlmTokens(String model, long promptTokens, long completionTokens) {
    if (promptTokens > 0)
      registry.counter(LLM_TOKENS, "type", "prompt", "model", model).increment(promptTokens);
    if (completionTokens > 0)
      registry
          .counter(LLM_TOKENS, "type", "completion", "model", model)
          .increment(completionTokens);
  }

  /** 绑定「当前排队任务数」数据源；换队列实现时只改这一处绑定。 */
  void bindQueueDepth(Supplier<Number> depthSupplier) {
    Gauge.builder(QUEUE_DEPTH, depthSupplier, supplier -> supplier.get().doubleValue())
        .description("当前排队等待处理的任务数")
        .strongReference(true)
        .register(registry);
  }

  /** 任务从入队到真正开始执行的等待时长。 */
  void recordQueueWait(Duration wait) {
    Timer.builder(QUEUE_WAIT)
        .description("任务从入队到开始执行的等待时长")
        .publishPercentileHistogram()
        .register(registry)
        .record(wait);
  }
}
