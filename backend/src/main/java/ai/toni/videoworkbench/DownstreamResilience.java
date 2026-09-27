package ai.toni.videoworkbench;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * 下游调用的容错外壳：并发隔板（外层）+ 熔断器（内层）。
 *
 * <p>顺序不能反：隔板在最外层，本地并发打满时立即拒绝，既不占着线程等，也不会把「本地资源不足」 记成下游故障而把熔断器打开。
 *
 * <p>这里<strong>没有</strong>再叠一层 Resilience4j 的重试与超时，因为同一件事已经有明确的归属：
 *
 * <ul>
 *   <li>重试：单次 LLM 请求内部有「主模型 → 备用模型」降级链，摘要校验失败还有带上失败原因的 3 次重试， 瞬时故障另有队列的指数退避兜底（见 {@code
 *       TaskQueueConsumer}）。再加一层 Retry 会让实际尝试次数相乘， 把 token 成本与耗时放大到无法解释。
 *   <li>超时：由 JDK {@code HttpRequest.timeout} 在每次请求上强制，这是唯一能真正终止阻塞 I/O 的位置； Resilience4j 的
 *       TimeLimiter 是「另起线程 + 到点返回」，被放弃的是等待方而不是跑着网络请求的线程， 用它只会把「还在跑的请求」藏起来，不如不加。
 * </ul>
 *
 * <p>熔断状态通过 {@code /actuator/circuitbreakers} 与 Micrometer 指标 {@code
 * resilience4j_circuitbreaker_state} 暴露（阈值见 application.yml，说明见
 * README「可观测性」）。**没有**注册健康指示器：熔断打开是「下游暂时不可用」， 不代表本进程不健康，把它算进 {@code /actuator/health}
 * 会让容器健康检查失败并被无谓重启。
 */
@Component
class DownstreamResilience {
  /** 受保护的下游；{@code id} 同时是 application.yml 里 resilience4j 的实例名。 */
  enum Target {
    LLM,
    WHISPER;

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final CircuitBreakerRegistry circuitBreakers;
  private final BulkheadRegistry bulkheads;

  DownstreamResilience(CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads) {
    this.circuitBreakers = circuitBreakers;
    this.bulkheads = bulkheads;
  }

  /**
   * 用默认注册表构造一个不受 application.yml 约束的实例（阈值宽松，实际不会拦路）。
   *
   * <p>给不起 Spring 上下文的调用方用：单元测试只需要一个「不拦路」的壳，不该被迫复制一份生产阈值。
   */
  static DownstreamResilience withDefaults() {
    return new DownstreamResilience(
        CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
  }

  /**
   * 执行一次下游调用。
   *
   * @throws CallNotPermittedException 熔断器已打开，本次没有真正发起请求
   * @throws BulkheadFullException 并发隔板已满，本次没有真正发起请求
   */
  <T> T call(Target target, Callable<T> action) throws Exception {
    Callable<T> guarded =
        Bulkhead.decorateCallable(
            bulkheads.bulkhead(target.id()),
            CircuitBreaker.decorateCallable(circuitBreakers.circuitBreaker(target.id()), action));
    return guarded.call();
  }

  /**
   * 本次失败是否属于「被本地容错策略拒绝」。
   *
   * <p>这类失败请求根本没打出去，语义上是「等一下再试」而不是「下游返回了错误」，调用方据此归入瞬时故障走退避重试。
   */
  static boolean isRejected(Throwable ex) {
    return ex instanceof CallNotPermittedException || ex instanceof BulkheadFullException;
  }
}
