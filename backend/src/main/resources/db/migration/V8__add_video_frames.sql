-- P1 视觉分支：场景检测抽出的关键帧与其 OCR 文本。
-- phash 是 64 位感知哈希（十进制有符号），用于相邻帧去重与排查；
-- ocr_text 允许为空：OCR 是尽力而为的支线，失败时帧仍然保留（前端可展示缩略图），
-- 整帧 OCR 都失败也不阻断主链路（见 docs/改造方案与优先级.md P1 决策 #4）。
create table video_frames (
  id bigint not null auto_increment,
  task_id char(36) not null,
  ts_ms bigint not null,
  ocr_text text null,
  phash bigint not null,
  path varchar(512) not null,
  primary key (id),
  unique key uk_video_frames_task_ts (task_id, ts_ms),
  constraint fk_video_frames_task foreign key (task_id) references tasks(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
