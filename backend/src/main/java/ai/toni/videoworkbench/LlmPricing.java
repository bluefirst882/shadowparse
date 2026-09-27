package ai.toni.videoworkbench;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型单价表，只用于把已入库的 token 用量折算成金额。
 *
 * <p>单价必须由使用者显式配置（{@code LLM_PRICES}），格式 {@code 模型:每百万输入token单价:每百万输出token单价}， 多条用逗号分隔，例如 {@code
 * deepseek-v4-flash:0.5:1.5,deepseek-v4-pro:2:8}。**代码里不内置任何价格**：
 * 价格随账号、区域与调价变动，写死在仓库里迟早变成过期数字，还会让「成本」看起来像官方结论。
 *
 * <p>未配置单价的模型不计金额（导出的成本为 {@code null}，只给 token 数），不做「按同价估算」的兜底。 配置格式非法在启动时直接失败，避免线上静默把成本算成 0。
 */
@Component
class LlmPricing {

  private final Map<String, Price> prices;

  LlmPricing(
      @Value("${workbench.llm.prices:}") String specification,
      @Value("${workbench.llm.price-currency:USD}") String currency) {
    this.prices = parse(specification);
    this.currency = currency;
  }

  private final String currency;

  String currency() {
    return currency;
  }

  Optional<Price> of(String model) {
    return Optional.ofNullable(prices.get(model));
  }

  static Map<String, Price> parse(String specification) {
    Map<String, Price> parsed = new LinkedHashMap<>();
    for (String entry : specification.split(",")) {
      String value = entry.strip();
      if (value.isEmpty()) continue;
      String[] parts = value.split(":");
      if (parts.length != 3)
        throw new IllegalStateException("LLM_PRICES 条目必须是 模型:每百万输入单价:每百万输出单价，实际为 " + value);
      String model = parts[0].strip();
      if (model.isEmpty()) throw new IllegalStateException("LLM_PRICES 条目缺少模型名：" + value);
      parsed.put(model, new Price(number(parts[1], value), number(parts[2], value)));
    }
    return parsed;
  }

  private static double number(String text, String entry) {
    double value;
    try {
      value = Double.parseDouble(text.strip());
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("LLM_PRICES 单价不是数字：" + entry);
    }
    if (value < 0) throw new IllegalStateException("LLM_PRICES 单价不能为负：" + entry);
    return value;
  }

  /** 每百万 token 的单价。 */
  record Price(double promptPerMillion, double completionPerMillion) {

    /** 按用量折算金额，保留 6 位小数（单次调用金额远小于 1 个货币单位）。 */
    BigDecimal cost(long promptTokens, long completionTokens) {
      return BigDecimal.valueOf(promptTokens)
          .multiply(BigDecimal.valueOf(promptPerMillion))
          .add(
              BigDecimal.valueOf(completionTokens)
                  .multiply(BigDecimal.valueOf(completionPerMillion)))
          .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
    }
  }
}
