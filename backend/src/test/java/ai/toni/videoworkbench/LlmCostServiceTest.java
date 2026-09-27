package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 用内存数据库验证 P1-4 的用量落库与成本折算：按提示词版本 + 模型分行、只统计本任务、缺单价不给金额。
 *
 * <p>表结构对齐 {@code V4__add_llm_calls.sql}，让聚合 SQL 在测试里跑的是与线上同一份写法。
 */
class LlmCostServiceTest {
  private JdbcTemplate jdbc;
  private LlmUsageRepository usage;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl("jdbc:h2:mem:llm-calls-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        create table llm_calls (
          id bigint auto_increment primary key,
          task_id varchar(36) not null,
          operation varchar(32) not null,
          prompt_id varchar(64) not null,
          model varchar(64) not null,
          prompt_tokens int not null,
          completion_tokens int not null,
          created_at timestamp(3) not null
        )
        """);
    usage = new LlmUsageRepository(jdbc);
  }

  @Test
  void aggregatesUsageByPromptVersionAndModelAndSumsCosts() {
    record("task-1", "summarize.v1", "flash", 1000, 500);
    record("task-1", "summarize.v1", "flash", 2000, 1000);
    record("task-1", "summarize.v1", "pro", 3000, 4000);
    record("task-1", "translate.v1", "flash", 500, 600);
    record("task-2", "summarize.v1", "flash", 9999, 9999);

    TaskCost cost = costs("flash:1:2,pro:10:20").costOf("task-1");

    assertEquals(3, cost.lines().size(), "应按 prompt 版本 + 模型分三行");
    assertEquals(6500, cost.promptTokens());
    assertEquals(6100, cost.completionTokens());
    // flash 摘要 0.006 + pro 摘要 0.11 + flash 翻译 0.0017
    assertEquals(new BigDecimal("0.117700"), cost.estimatedCost());
    assertEquals("USD", cost.currency());

    TaskCost.Line first = cost.lines().get(0);
    assertEquals("summarize.v1", first.promptId());
    assertEquals("flash", first.model());
    assertEquals(2, first.calls(), "同一版本同一模型的多次调用应合并为一行并计数");
    assertEquals(3000, first.promptTokens());
    assertEquals(1500, first.completionTokens());
    assertEquals(new BigDecimal("0.006000"), first.estimatedCost());
    assertEquals("pro", cost.lines().get(1).model(), "降级后的调用单独成行，降级成本可单独看到");
    assertEquals(new BigDecimal("0.110000"), cost.lines().get(1).estimatedCost());
  }

  @Test
  void leavesTotalCostNullWhenAnyModelHasNoPrice() {
    record("task-1", "summarize.v1", "flash", 1000, 1000);
    record("task-1", "summarize.v1", "pro", 1000, 1000);

    TaskCost cost = costs("flash:1:1").costOf("task-1");

    assertNull(cost.estimatedCost(), "缺一行单价就不能给总数，避免算出看起来完整的错数");
    assertNull(cost.lines().get(1).estimatedCost());
    assertEquals(new BigDecimal("0.002000"), cost.lines().get(0).estimatedCost());
    assertEquals(2000, cost.promptTokens(), "单价缺失不影响 token 统计");
  }

  @Test
  void returnsEmptyUsageForTaskWithoutAnyCall() {
    TaskCost cost = costs("flash:1:1").costOf("task-without-calls");

    assertTrue(cost.lines().isEmpty());
    assertEquals(0, cost.promptTokens());
    assertEquals(0, cost.completionTokens());
    assertEquals(0, BigDecimal.ZERO.compareTo(cost.estimatedCost()));
  }

  @Test
  void rejectsMalformedPriceSpecification() {
    assertThrows(IllegalStateException.class, () -> LlmPricing.parse("flash:1"));
    assertThrows(IllegalStateException.class, () -> LlmPricing.parse(":1:2"));
    assertThrows(IllegalStateException.class, () -> LlmPricing.parse("flash:x:2"));
    assertThrows(IllegalStateException.class, () -> LlmPricing.parse("flash:-1:2"));
    // 空配置（未填 LLM_PRICES）是允许的：只统计 token，不折算金额。
    assertTrue(LlmPricing.parse("").isEmpty());
  }

  @Test
  void usageBookkeepingFailureDoesNotBreakTheCall() {
    // 记账表缺失时，写账必须只告警：不能因为记不上账而让一次已经成功的生成调用失败。
    LlmUsageRepository broken = new LlmUsageRepository(new JdbcTemplate(emptyDataSource()));

    assertDoesNotThrow(
        () ->
            broken.record(
                new LlmUsageRepository.LlmCall(
                    "task-1", "summarize", "summarize.v1", "flash", 1, 1)));
  }

  private void record(
      String taskId, String promptId, String model, long promptTokens, long completionTokens) {
    usage.record(
        new LlmUsageRepository.LlmCall(
            taskId,
            promptId.startsWith("translate") ? "translate" : "summarize",
            promptId,
            model,
            promptTokens,
            completionTokens));
  }

  private LlmCostService costs(String prices) {
    return new LlmCostService(usage, new LlmPricing(prices, "USD"));
  }

  private DriverManagerDataSource emptyDataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl("jdbc:h2:mem:llm-calls-empty-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
