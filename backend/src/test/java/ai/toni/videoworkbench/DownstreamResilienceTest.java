package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 容错外壳的两种「本地拒绝」：熔断打开后不再发请求、并发隔板满了立即拒绝而不是排队等。 */
class DownstreamResilienceTest {
  private static final Duration BUDGET = Duration.ofMinutes(5);

  @Test
  void stopsCallingDownstreamOnceCircuitOpens() throws Exception {
    // 阈值调到 2 次即可判定：连续两次失败后熔断器打开，第 3 次必须直接被拒。
    CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    breakers.circuitBreaker(
        "whisper",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(BUDGET)
            .build());
    DownstreamResilience resilience =
        new DownstreamResilience(breakers, BulkheadRegistry.ofDefaults());
    AtomicInteger calls = new AtomicInteger();
    Callable<String> failing =
        () -> {
          calls.incrementAndGet();
          throw new IOException("Whisper 服务不可达");
        };

    assertThrows(
        IOException.class, () -> resilience.call(DownstreamResilience.Target.WHISPER, failing));
    assertThrows(
        IOException.class, () -> resilience.call(DownstreamResilience.Target.WHISPER, failing));
    CallNotPermittedException rejected =
        assertThrows(
            CallNotPermittedException.class,
            () -> resilience.call(DownstreamResilience.Target.WHISPER, failing));

    assertEquals(2, calls.get(), "熔断打开后不应再真正调用下游");
    assertTrue(DownstreamResilience.isRejected(rejected));
    assertEquals(CircuitBreaker.State.OPEN, breakers.circuitBreaker("whisper").getState());
    assertEquals(
        1.0,
        breakers.circuitBreaker("whisper").getMetrics().getNumberOfNotPermittedCalls(),
        "被拒绝的调用应计入 not_permitted，供 Prometheus 侧观测");
  }

  @Test
  void rejectsInsteadOfQueueingWhenConcurrencyIsSaturated() throws Exception {
    BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    bulkheads.bulkhead(
        "llm",
        BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    DownstreamResilience resilience =
        new DownstreamResilience(CircuitBreakerRegistry.ofDefaults(), bulkheads);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<String> holding =
          pool.submit(
              () ->
                  resilience.call(
                      DownstreamResilience.Target.LLM,
                      () -> {
                        entered.countDown();
                        release.await();
                        return "first";
                      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS), "第一个调用应已占用隔板");

      Future<String> second =
          pool.submit(() -> resilience.call(DownstreamResilience.Target.LLM, () -> "second"));
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));

      assertInstanceOf(BulkheadFullException.class, failure.getCause());
      assertTrue(DownstreamResilience.isRejected(failure.getCause()));

      release.countDown();
      assertEquals("first", holding.get(2, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void defaultsAllowOrdinaryCallsAndTreatDownstreamErrorsAsOrdinaryFailures() throws Exception {
    DownstreamResilience resilience = DownstreamResilience.withDefaults();

    assertEquals("ok", resilience.call(DownstreamResilience.Target.LLM, () -> "ok"));
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                resilience.call(
                    DownstreamResilience.Target.LLM,
                    () -> {
                      throw new IOException("服务返回 500");
                    }));
    assertTrue(!DownstreamResilience.isRejected(failure), "下游自己报的错不是本地拒绝，不能按退避重试处理");
  }
}
