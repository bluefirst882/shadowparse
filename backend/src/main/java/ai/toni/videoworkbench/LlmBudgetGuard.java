package ai.toni.videoworkbench;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM 日 token 预算守卫：管「一共花多少」，与速率限制（花多快）、隔板/熔断（同时多少个）正交。
 *
 * <p>预算按「用户记账、全局兜底」设计：账目经 {@code tasks.owner_id} 关联到用户，
 * 用户级预算未配置时只有全局闸生效——先自用只有一把全局闸，将来少数人共用时打开用户配额即可，不需要改代码。
 *
 * <p>超预算的处理（见 docs/改造方案与优先级.md P0 决策 #3）是<b>降级续跑</b>而不是失败： 调用方（{@link LlmClient}）查 {@link
 * #overBudget}，超限就把这次调用切到廉价档模型， 质量有损但任务当日可用；廉价档未配置时才退回「任务 FAILED」。
 *
 * <p>预算口径是「自然日、系统默认时区」：账目的 {@code created_at} 就是用 JVM 默认时区写入的， 口径一致才不会出现「账面跨天但检查没跨天」的空档。
 */
@Component
class LlmBudgetGuard {
  private static final Logger log = LoggerFactory.getLogger(LlmBudgetGuard.class);

  private final LlmUsageRepository usage;
  private final WorkbenchMetrics metrics;
  private final long dailyBudget;
  private final long userDailyBudget;
  private final String fallbackModel;

  LlmBudgetGuard(
      LlmUsageRepository usage,
      WorkbenchMetrics metrics,
      @Value("${workbench.llm.daily-token-budget:0}") long dailyBudget,
      @Value("${workbench.llm.user-daily-token-budget:0}") long userDailyBudget,
      @Value("${workbench.llm.budget-fallback-model:}") String fallbackModel) {
    this.usage = usage;
    this.metrics = metrics;
    this.dailyBudget = Math.max(0, dailyBudget);
    this.userDailyBudget = Math.max(0, userDailyBudget);
    this.fallbackModel = fallbackModel == null ? "" : fallbackModel.strip();
    metrics.bindBudgetUsedToday(() -> usage.totalTokensSince(startOfToday()));
    metrics.bindBudgetLimit(this::effectiveBudget);
  }

  /** 是否有任何口径启用了预算；未启用时调用方零开销（不查库）。 */
  boolean enabled() {
    return dailyBudget > 0 || userDailyBudget > 0;
  }

  /** 预算超限后使用的廉价档模型；空表示未配置，超限直接走失败路径。 */
  String cheapModel() {
    return fallbackModel;
  }

  /**
   * 当日预算是否已用完（{@code used >= limit}）。任一启用的口径触顶即超限。
   *
   * <p>不做分布式锁、不预扣未来的调用：预算检查与真实调用之间有竞态（并发块同时通过检查）， 多算的部分由调用后的 usage 入账兜底，误差一轮分块内可接受。
   */
  boolean overBudget(String taskId) {
    if (!enabled()) return false;
    Instant startOfDay = startOfToday();
    if (dailyBudget > 0) {
      long used = usage.totalTokensSince(startOfDay);
      if (used >= dailyBudget) {
        log.warn("全局日预算已用完：used={} budget={}", used, dailyBudget);
        return true;
      }
    }
    if (userDailyBudget > 0) {
      long used = usage.userTokensSince(taskId, startOfDay);
      if (used >= userDailyBudget) {
        log.warn("用户日预算已用完：taskId={} used={} budget={}", taskId, used, userDailyBudget);
        return true;
      }
    }
    return false;
  }

  /** Grafana 用的预算上限读数：全局与用户预算取大者，0 表示未启用。 */
  private long effectiveBudget() {
    return Math.max(dailyBudget, userDailyBudget);
  }

  private static Instant startOfToday() {
    return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
  }
}
