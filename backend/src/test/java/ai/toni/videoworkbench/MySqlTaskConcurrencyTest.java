package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            new ObjectMapper());
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    tasks.save(
        new VideoTask(
            id,
            "concurrency-test.mp4",
            "test://" + id,
            0,
            TaskStatus.QUEUED,
            TaskStage.IMPORT,
            0,
            null,
            now,
            now));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<Boolean>> results =
          List.of(
              workers.submit(claimTask(tasks, id, ready, start)),
              workers.submit(claimTask(tasks, id, ready, start)));
      org.junit.jupiter.api.Assertions.assertTrue(ready.await(1, TimeUnit.SECONDS));
      start.countDown();
      assertEquals(1, results.stream().filter(result -> get(result)).count());
    } finally {
      start.countDown();
      workers.shutdownNow();
      workers.awaitTermination(1, TimeUnit.SECONDS);
      tasks.delete(id);
    }
  }

  private Callable<Boolean> claimTask(
      TaskRepository tasks, String id, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await(1, TimeUnit.SECONDS);
      return tasks.claimForProcessing(id);
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
