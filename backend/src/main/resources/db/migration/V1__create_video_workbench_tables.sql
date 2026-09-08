create table tasks (
  id char(36) primary key,
  file_name varchar(512) not null,
  video_path varchar(2048) not null,
  size_bytes bigint not null,
  status varchar(32) not null,
  stage varchar(32) not null,
  progress int not null default 0,
  error_message text,
  created_at timestamp(3) not null,
  updated_at timestamp(3) not null,
  cancelled boolean not null default false,
  index idx_tasks_updated_at (updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table transcript_segments (
  id bigint primary key auto_increment,
  task_id char(36) not null,
  start_ms bigint not null,
  end_ms bigint not null,
  text text not null,
  constraint fk_transcript_task foreign key (task_id) references tasks(id) on delete cascade,
  index idx_transcript_task_time (task_id, start_ms)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table task_results (
  task_id char(36) primary key,
  summary text not null,
  key_points_json json not null,
  chapters_json json not null,
  constraint fk_result_task foreign key (task_id) references tasks(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
