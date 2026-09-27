package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 用内存注册表断言五类自定义指标的注册名、标签与计数，并确认 Prometheus 导出名符合约定。
 *
 * <p>不依赖 Spring 容器与真实外部服务，保证埋点行为可被快速回归。
 */
class WorkbenchMetricsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WorkbenchMetrics metrics = new WorkbenchMetrics(registry);

  @Test
  void recordsTranscriptionDurationByOutcome() {
    metrics.recordTranscription(Duration.ofSeconds(2), true);
    metrics.recordTranscription(Duration.ofMillis(1500), true);
    metrics.recordTranscription(Duration.ofSeconds(1), false);

    assertEquals(
        2,
        registry
            .get(WorkbenchMetrics.TRANSCRIPTION_DURATION)
            .tag("outcome", "success")
            .timer()
            .count());
    assertEquals(
        3500.0,
        registry
            .get(WorkbenchMetrics.TRANSCRIPTION_DURATION)
            .tag("outcome", "success")
            .timer()
            .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS),
        0.001);
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.TRANSCRIPTION_DURATION)
            .tag("outcome", "failure")
            .timer()
            .count());
  }

  @Test
  void recordsLlmCallsAndRetries() {
    metrics.recordLlmCall("summarize", true);
    metrics.recordLlmCall("summarize", true);
    metrics.recordLlmCall("summarize", false);
    metrics.recordLlmCall("translate", true);
    metrics.recordLlmRetry("summarize");
    metrics.recordLlmRetry("summarize");

    assertEquals(
        2,
        registry
            .get(WorkbenchMetrics.LLM_REQUESTS)
            .tags("operation", "summarize", "outcome", "success")
            .counter()
            .count());
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_REQUESTS)
            .tags("operation", "summarize", "outcome", "failure")
            .counter()
            .count());
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_REQUESTS)
            .tags("operation", "translate", "outcome", "success")
            .counter()
            .count());
    assertEquals(
        2,
        registry.get(WorkbenchMetrics.LLM_RETRIES).tag("operation", "summarize").counter().count());
  }

  @Test
  void recordsLlmValidationFailuresByReason() {
    metrics.recordLlmValidationFailure("quote_mismatch");
    metrics.recordLlmValidationFailure("quote_mismatch");
    metrics.recordLlmValidationFailure("invalid_chapter_timing");

    assertEquals(
        2,
        registry
            .get(WorkbenchMetrics.LLM_VALIDATION_FAILURES)
            .tag("reason", "quote_mismatch")
            .counter()
            .count());
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_VALIDATION_FAILURES)
            .tag("reason", "invalid_chapter_timing")
            .counter()
            .count());
  }

  @Test
  void recordsFallbacksAndStructuredOutputFailuresByReason() {
    metrics.recordLlmFallback("summarize", "http_400");
    metrics.recordLlmFallback("summarize", "http_400");
    metrics.recordLlmFallback("summarize", "schema_violation");
    metrics.recordLlmOutputFailure("summarize", "schema_violation");
    metrics.recordLlmOutputFailure("translate", "invalid_json");

    assertEquals(
        2,
        registry
            .get(WorkbenchMetrics.LLM_FALLBACKS)
            .tags("operation", "summarize", "reason", "http_400")
            .counter()
            .count());
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_FALLBACKS)
            .tags("operation", "summarize", "reason", "schema_violation")
            .counter()
            .count());
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_OUTPUT_FAILURES)
            .tags("operation", "summarize", "reason", "schema_violation")
            .counter()
            .count());
    assertEquals(
        1,
        registry
            .get(WorkbenchMetrics.LLM_OUTPUT_FAILURES)
            .tags("operation", "translate", "reason", "invalid_json")
            .counter()
            .count());
  }

  @Test
  void accumulatesPromptAndCompletionTokensPerModel() {
    metrics.recordLlmTokens("deepseek-v4-flash", 100, 40);
    metrics.recordLlmTokens("deepseek-v4-flash", 20, 10);

    assertEquals(
        120,
        registry
            .get(WorkbenchMetrics.LLM_TOKENS)
            .tags("type", "prompt", "model", "deepseek-v4-flash")
            .counter()
            .count());
    assertEquals(
        50,
        registry
            .get(WorkbenchMetrics.LLM_TOKENS)
            .tags("type", "completion", "model", "deepseek-v4-flash")
            .counter()
            .count());
  }

  @Test
  void queueDepthGaugeFollowsBoundSupplierAndQueueWaitIsTimed() {
    AtomicInteger depth = new AtomicInteger(3);
    metrics.bindQueueDepth(depth::get);

    Gauge gauge = registry.get(WorkbenchMetrics.QUEUE_DEPTH).gauge();
    assertEquals(3.0, gauge.value());
    depth.set(0);
    assertEquals(0.0, gauge.value());

    metrics.recordQueueWait(Duration.ofMillis(250));
    assertEquals(1, registry.get(WorkbenchMetrics.QUEUE_WAIT).timer().count());
    assertEquals(
        250.0,
        registry
            .get(WorkbenchMetrics.QUEUE_WAIT)
            .timer()
            .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS),
        0.001);
  }

  @Test
  void exposesExpectedPrometheusMetricNames() {
    PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    WorkbenchMetrics prometheusMetrics = new WorkbenchMetrics(prometheus);
    prometheusMetrics.recordTranscription(Duration.ofSeconds(1), true);
    prometheusMetrics.recordLlmCall("summarize", true);
    prometheusMetrics.recordLlmRetry("summarize");
    prometheusMetrics.recordLlmFallback("summarize", "http_400");
    prometheusMetrics.recordLlmOutputFailure("summarize", "schema_violation");
    prometheusMetrics.recordLlmValidationFailure("quote_mismatch");
    prometheusMetrics.recordLlmTokens("deepseek-v4-flash", 10, 5);
    prometheusMetrics.bindQueueDepth(() -> 1);
    prometheusMetrics.recordQueueWait(Duration.ofMillis(10));

    String scrape = prometheus.scrape();
    for (String name :
        new String[] {
          "workbench_transcription_duration_seconds_count",
          "workbench_transcription_duration_seconds_bucket",
          "workbench_llm_requests_total",
          "workbench_llm_retries_total",
          "workbench_llm_fallbacks_total",
          "workbench_llm_output_failures_total",
          "workbench_llm_validation_failures_total",
          "workbench_llm_tokens_total",
          "workbench_queue_depth",
          "workbench_queue_wait_seconds_count"
        }) assertTrue(scrape.contains(name), "缺少 Prometheus 指标：" + name);
  }
}
