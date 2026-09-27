create table users (
  id char(36) primary key,
  username varchar(64) not null,
  password_hash varchar(100) not null,
  created_at timestamp(3) not null,
  unique key uk_users_username (username)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

alter table tasks add column owner_id char(36) null after id;

-- 种子用户承接鉴权上线前已存在的历史任务，密码为 demo1234（仅本地开发使用）。
insert into users(id, username, password_hash, created_at)
values (
  '00000000-0000-0000-0000-000000000001',
  'demo',
  '$2a$10$cJCnxNR82u487LMmfBO6DOzPE9Ir9De89.fsf0e9RCuoiRtxO649u',
  now(3)
);

update tasks set owner_id = '00000000-0000-0000-0000-000000000001' where owner_id is null;

-- 回填完成后再收紧约束，保证历史任务的转写与摘要数据不丢失。
alter table tasks modify column owner_id char(36) not null;

alter table tasks add constraint fk_tasks_owner foreign key (owner_id) references users(id);
alter table tasks add index idx_tasks_owner_created (owner_id, created_at);
