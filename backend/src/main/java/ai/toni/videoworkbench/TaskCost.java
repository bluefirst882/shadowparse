package ai.toni.videoworkbench;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单个视频（任务）的 LLM 成本账单，按提示词版本 + 实际模型分行。
 *
 * <p>{@code estimatedCost} 在「所有行都配了单价」时才是金额，否则为 {@code null}：缺一行单价就不给总数，
 * 免得把「部分缺失」算成一个看起来完整的数字。{@code promptTokens} / {@code completionTokens} 与单价无关，始终有值。
 */
record TaskCost(
    String taskId,
    String currency,
    long promptTokens,
    long completionTokens,
    BigDecimal estimatedCost,
    List<Line> lines) {

  record Line(
      String promptId,
      String model,
      long calls,
      long promptTokens,
      long completionTokens,
      BigDecimal estimatedCost) {}
}
