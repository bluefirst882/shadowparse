package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MySqlTaskConcurrencyTest {
  @Test
  void onlyOneConcurrentWorkerCanClaimQueuedTask() throws Exception {
    String url = System.getenv("MYSQL_TEST_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置 MySQL 集成测试数据库");
    String username = System.getenv("MYSQL_TEST_USERNAME");
    String password = System.getenv("MYSQL_TEST_PASSWORD");
    Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    TaskRepository tasks =
        new TaskRepository(
            new org.springframework.jdbc.core.JdbcTemplate(dataSource(url, username, password)),
            new ObjectMapper(),
            new TaskEventStream(
                new org.springframework.jdbc.core.JdbcTemplate(
                    dataSource(url, username, password))));
    org.springframework.jdbc.core.JdbcTemplate jdbc =
        new org.springframework.jdbc.core.JdbcTemplate(dataSource(url, username, password));
    UserRepository users = new UserRepository(jdbc);
    String owner = UUID.randomUUID().toString();
    users.save(new User(owner, "concurrency-" + owner.substring(0, 8), "unused-hash"));
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    tasks.save(
        new VideoTask(
            id,
            "concurrency-test.mp4",
            null,
            "test://" + id,
            0,
            TaskStatus.QUEUED,
            TaskStage.IMPORT,
            0,
            null,
            now,
            now),
        owner);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<Boolean>> results =
          List.of(
              workers.submit(claimTask(tasks, id, "instance-a", ready, start)),
              workers.submit(claimTask(tasks, id, "instance-b", ready, start)));
      org.junit.jupiter.api.Assertions.assertTrue(ready.await(1, TimeUnit.SECONDS));
      start.countDown();
      assertEquals(1, results.stream().filter(result -> get(result)).count());
    } finally {
      start.countDown();
      workers.shutdownNow();
      workers.awaitTermination(1, TimeUnit.SECONDS);
      tasks.delete(id, owner);
      jdbc.update("delete from users where id=?", owner);
    }
  }

  /**
   * 多实例接管的关键约束：只回收租约已过期的行。
   *
   * <p>租约仍有效的任务属于正在跑它的实例，回收它就会造成重复执行；租约过期的任务已经没人管，不回收则永远卡住。 两条断言一个都不能少，否则「多实例认领」只是看起来对。
   */
  @Test
  void reclaimsOnlyExpiredLeases() {
    String url = System.getenv("MYSQL_TEST_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置 MySQL 集成测试数据库");
    String username = System.getenv("MYSQL_TEST_USERNAME");
    String password = System.getenv("MYSQL_TEST_PASSWORD");
    Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    org.springframework.jdbc.core.JdbcTemplate jdbc =
        new org.springframework.jdbc.core.JdbcTemplate(dataSource(url, username, password));
    TaskRepository tasks = new TaskRepository(jdbc, new ObjectMapper(), new TaskEventStream(jdbc));
    UserRepository users = new UserRepository(jdbc);
    String owner = UUID.randomUUID().toString();
    users.save(new User(owner, "lease-" + owner.substring(0, 8), "unused-hash"));
    String liveId = UUID.randomUUID().toString();
    String expiredId = UUID.randomUUID().toString();
    String legacyId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    try {
      tasks.save(queued(liveId, now), owner);
      tasks.save(queued(expiredId, now), owner);
      tasks.save(queued(legacyId, now), owner);
      tasks.claimForProcessing(liveId, "instance-a", now.plusSeconds(90));
      tasks.claimForProcessing(expiredId, "instance-a", now.minusSeconds(1));
      // 迁移前遗留的 PROCESSING 行：没有租约，按「已过期」处理，升级过程中被中断的任务不能卡住。
      jdbc.update("update tasks set status='PROCESSING' where id=?", legacyId);

      assertEquals(
          List.of(expiredId, legacyId).stream().sorted().toList(),
          tasks.expiredClaims(now.plusMillis(1)).stream().sorted().toList());
      org.junit.jupiter.api.Assertions.assertTrue(
          tasks.releaseExpiredClaim(expiredId, now.plusMillis(1)));
      assertFalse(tasks.releaseExpiredClaim(liveId, now.plusMillis(1)));

      assertEquals("QUEUED", statusOf(jdbc, expiredId));
      assertEquals("PROCESSING", statusOf(jdbc, liveId));
      org.junit.jupiter.api.Assertions.assertNull(
          jdbc.queryForObject("select locked_by from tasks where id=?", String.class, expiredId));
    } finally {
      tasks.delete(liveId, owner);
      tasks.delete(expiredId, owner);
      tasks.delete(legacyId, owner);
      jdbc.update("delete from users where id=?", owner);
    }
  }

  private static VideoTask queued(String id, Instant now) {
    return new VideoTask(
        id,
        "lease-test.mp4",
        null,
        "test://" + id,
        0,
        TaskStatus.QUEUED,
        TaskStage.IMPORT,
        0,
        null,
        now,
        now);
  }

  private static String statusOf(org.springframework.jdbc.core.JdbcTemplate jdbc, String id) {
    return jdbc.queryForObject("select status from tasks where id=?", String.class, id);
  }

  private Callable<Boolean> claimTask(
      TaskRepository tasks,
      String id,
      String instanceId,
      CountDownLatch ready,
      CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await(1, TimeUnit.SECONDS);
      return tasks.claimForProcessing(id, instanceId, Instant.now().plusSeconds(90));
    };
  }

  private boolean get(java.util.concurrent.Future<Boolean> result) {
    try {
      return result.get(1, TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new AssertionError("并发领取任务失败", ex);
    }
  }

  private DriverManagerDataSource dataSource(String url, String username, String password) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(url);
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    return dataSource;
  }
}
