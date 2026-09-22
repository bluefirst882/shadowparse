package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class MySqlTranscriptTransactionTest {
  @Test
  void rollsBackWholeTranscriptReplacementWhenOneInsertFails() {
    String url = System.getenv("MYSQL_TEST_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置 MySQL 集成测试数据库");
    String username = System.getenv("MYSQL_TEST_USERNAME");
    String password = System.getenv("MYSQL_TEST_PASSWORD");
    Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          TestDatabaseConfig.class, () -> new TestDatabaseConfig(url, username, password));
      context.refresh();
      TaskRepository tasks = context.getBean(TaskRepository.class);
      String id = UUID.randomUUID().toString();
      saveTask(tasks, id);
      try {
        tasks.replaceSegments(id, List.of(new TranscriptSegment(0, 0, 1000, "原始片段", null)));

        assertThrows(
            RuntimeException.class,
            () ->
                tasks.replaceSegments(
                    id,
                    List.of(
                        new TranscriptSegment(0, 1000, 2000, "新片段", null),
                        new TranscriptSegment(0, 2000, 3000, null, null))));

        List<TranscriptSegment> segments = tasks.segments(id);
        assertEquals(1, segments.size());
        assertEquals("原始片段", segments.getFirst().text());
      } finally {
        tasks.delete(id);
      }
    }
  }

  private void saveTask(TaskRepository tasks, String id) {
    Instant now = Instant.now();
    tasks.save(
        new VideoTask(
            id,
            "transaction-test.mp4",
            "test://" + id,
            0,
            TaskStatus.QUEUED,
            TaskStage.IMPORT,
            0,
            null,
            now,
            now));
  }

  @Configuration
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TestDatabaseConfig {
    private final String url;
    private final String username;
    private final String password;

    TestDatabaseConfig(String url, String username, String password) {
      this.url = url;
      this.username = username;
      this.password = password;
    }

    @Bean
    DataSource dataSource() {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setUrl(url);
      dataSource.setUsername(username);
      dataSource.setPassword(password);
      return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    TaskRepository taskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
      return new TaskRepository(jdbcTemplate, objectMapper);
    }
  }
}
