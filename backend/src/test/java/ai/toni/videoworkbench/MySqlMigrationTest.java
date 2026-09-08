package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class MySqlMigrationTest {
  @Test
  void flywayCreatesCoreTables() throws Exception {
    String url = System.getenv("MYSQL_TEST_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置 MySQL 集成测试数据库");
    String username = System.getenv("MYSQL_TEST_USERNAME");
    String password = System.getenv("MYSQL_TEST_PASSWORD");
    Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration").load().migrate();
    try (Connection connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement(); var result = statement.executeQuery("select count(*) from information_schema.tables where table_schema = database() and table_name in ('tasks','transcript_segments','task_results')")) {
      result.next();
      assertEquals(3, result.getInt(1));
    }
  }
}
