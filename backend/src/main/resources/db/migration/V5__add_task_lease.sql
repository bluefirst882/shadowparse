-- 多实例认领：把「状态即锁」升级为「租约」。
--
-- 状态即锁在单实例下够用，多实例下有两个漏洞：
--   1. 启动恢复会执行 `update tasks set status='QUEUED' where status='PROCESSING'`，
--      把别的实例正在处理的任务也改回 QUEUED，于是同一任务被两个实例同时执行；
--   2. 无法区分「这条 PROCESSING 是别的实例在跑」与「是上一任持有者死了留下的」。
--
-- 改为记下持有者与租约到期时间：
--   * 领取时与状态一起原子写入 locked_by + lease_expires_at（条件仍是 status='QUEUED'）；
--   * 处理期间按心跳续租，只有本实例真正在跑的任务会被续租；
--   * 启动恢复与周期巡检只回收租约已过期的行，不再打断别人的活。
--
-- 迁移前遗留的 PROCESSING 行 locked_by / lease_expires_at 为 NULL，按「租约已过期」回收，
-- 因此升级过程中被中断的任务不会卡住。
alter table tasks
  add column locked_by varchar(64) null after cancelled,
  add column lease_expires_at timestamp(3) null after locked_by,
  add index idx_tasks_lease (status, lease_expires_at);
