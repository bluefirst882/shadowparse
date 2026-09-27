create table llm_calls (
  id bigint not null auto_increment,
  task_id char(36) not null,
  operation varchar(32) not null,
  prompt_id varchar(64) not null,
  model varchar(64) not null,
  prompt_tokens int not null,
  completion_tokens int not null,
  created_at timestamp(3) not null,
  primary key (id),
  key idx_llm_calls_task (task_id, created_at),
  -- 按 prompt 版本 + 模型聚合，用于对比不同提示词版本的用量与成本。
  key idx_llm_calls_prompt (prompt_id, model, created_at),
  constraint fk_llm_calls_task foreign key (task_id) references tasks(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
