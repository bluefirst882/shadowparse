-- P0 成本护栏：标记一次 LLM 调用是否因「当日预算超限」而切换到廉价档模型。
-- 降级链的正常降级（主模型失败换备用）不需要这个标记：model 字段已经记录了实际模型；
-- 这个标记区分的是「为什么用了廉价档」——预算超限（budget_fallback=1）还是主模型故障（0）。
alter table llm_calls
  add column budget_fallback tinyint(1) not null default 0 after model;
