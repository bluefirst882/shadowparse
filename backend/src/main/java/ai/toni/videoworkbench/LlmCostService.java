package ai.toni.videoworkbench;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 把已入库的用量折算成单个视频的 LLM 成本。
 *
 * <p>按 {@code prompt_id + model} 分行，是为了能回答「换了提示词版本之后，用量和成本变了多少」以及
 * 「主模型失败降级到备用模型后，这一个视频多花了多少」——这两件事只有把提示词版本和模型都记下来才算得出来。
 */
@Service
class LlmCostService {
  private final LlmUsageRepository usage;
  private final LlmPricing pricing;

  LlmCostService(LlmUsageRepository usage, LlmPricing pricing) {
    this.usage = usage;
    this.pricing = pricing;
  }

  TaskCost costOf(String taskId) {
    List<TaskCost.Line> lines = new ArrayList<>();
    long promptTokens = 0;
    long completionTokens = 0;
    BigDecimal total = BigDecimal.ZERO;
    boolean allPriced = true;
    for (LlmUsageRepository.Usage row : usage.summarize(taskId)) {
      BigDecimal cost =
          pricing
              .of(row.model())
              .map(price -> price.cost(row.promptTokens(), row.completionTokens()))
              .orElse(null);
      if (cost == null) allPriced = false;
      else total = total.add(cost);
      promptTokens += row.promptTokens();
      completionTokens += row.completionTokens();
      lines.add(
          new TaskCost.Line(
              row.promptId(),
              row.model(),
              row.calls(),
              row.promptTokens(),
              row.completionTokens(),
              cost));
    }
    return new TaskCost(
        taskId,
        pricing.currency(),
        promptTokens,
        completionTokens,
        allPriced ? total : null,
        List.copyOf(lines));
  }
}
