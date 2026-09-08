# MySQL 部署说明

项目使用 MySQL 8.0+，字符集为 `utf8mb4`。任务、转写、摘要、要点及章节均保存在 MySQL；视频和提取音频仍保留在 `WORKBENCH_STORAGE_DIR` 本地目录。

## 本地数据库

复制 `.env.example` 为 `.env`，至少设置：

```properties
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=video_workbench
MYSQL_USERNAME=video_workbench
MYSQL_PASSWORD=请替换为强密码
```

创建数据库与账号：

```sql
create database video_workbench character set utf8mb4 collate utf8mb4_0900_ai_ci;
create user 'video_workbench'@'localhost' identified by '请替换为强密码';
grant all privileges on video_workbench.* to 'video_workbench'@'localhost';
flush privileges;
```

也可设置 `MYSQL_ROOT_PASSWORD` 后运行 `docker compose up -d mysql`。后端启动时由 Flyway 执行 `V1__create_video_workbench_tables.sql`，不使用 `schema.sql` 或 JPA 自动建表。

CI 使用 MySQL 8.4 服务执行同一份迁移；本机的 `MySqlMigrationIT` 在未设置 `MYSQL_TEST_URL` 时会跳过，避免开发环境意外操作数据库。

## 迁移规则

已执行的 Flyway 文件不得修改。后续结构变更新增编号更大的文件，例如 `V2__add_task_duration.sql`。生产数据库迁移前应先备份；应用不会自动删除任务或视频文件。
