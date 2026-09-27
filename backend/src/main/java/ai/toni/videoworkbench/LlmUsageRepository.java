package ai.toni.videoworkbench;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * LLM 调用用量落库。
 *
 * <p>表里同时记下 {@code prompt_id}（提示词版本）与 {@code model}（实际服务的模型，降级链下可能是备用模型），
 * 因此「按提示词版本对比成本」和「降级后成本变化」都能直接查出来，不用去猜。
 *
 * <p>只记真实消耗了 token 的调用：响应体里没有 {@code usage} 时不写库，失败但有响应体的调用照样写—— token 确实被消耗了。
 *
 * <p>这是账目而不是业务主流程：写库失败只告警，不能让一次已经成功的摘要生成因为记账失败而回滚成失败。
 */
@Repository
class LlmUsageRepository {
  private final JdbcTemplate jdbc;
  private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmUsageRepository.class);

  LlmUsageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void record(LlmCall call) {
    try {
      jdbc.update(
          "insert into llm_calls(task_id,operation,prompt_id,model,prompt_tokens,completion_tokens,created_at) values(?,?,?,?,?,?,?)",
          call.taskId(),
          call.operation(),
          call.promptId(),
          call.model(),
          call.promptTokens(),
          call.completionTokens(),
          Timestamp.from(Instant.now()));
    } catch (RuntimeException ex) {
      log.warn("LLM 用量记账失败：{}", ex.getMessage());
    }
  }

  /** 按 {@code prompt_id + model} 聚合某个任务的用量；任务没有任何调用时返回空列表。 */
  List<Usage> summarize(String taskId) {
    return jdbc.query(
        """
        select prompt_id, model, count(*) as calls,
               sum(prompt_tokens) as prompt_tokens, sum(completion_tokens) as completion_tokens
        from llm_calls where task_id=?
        group by prompt_id, model
        order by prompt_id, model
        """,
        (r, n) ->
            new Usage(
                r.getString("prompt_id"),
                r.getString("model"),
                r.getLong("calls"),
                r.getLong("prompt_tokens"),
                r.getLong("completion_tokens")),
        taskId);
  }

  /** 一次 LLM 调用的账目：谁（任务）、用哪版提示词、实际哪个模型、花了多少 token。 */
  record LlmCall(
      String taskId,
      String operation,
      String promptId,
      String model,
      long promptTokens,
      long completionTokens) {}

  /** 聚合后的用量行。 */
  record Usage(
      String promptId, String model, long calls, long promptTokens, long completionTokens) {}
}
