-- 任务自定义名称：默认空，前端在为空时回退显示 file_name。
-- 改名只更新该列、不动 updated_at：列表按 created_at 排序，相对时间按 updated_at 展示，
-- 改名不应伪装成一次处理进度变化。
alter table tasks add column display_name varchar(200) null;
