package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 用内存数据库跑真实的键集分页 SQL，证明游标逐页取完时任务集合「不重复、不缺失」。
 *
 * <p>表结构对齐迁移脚本的 {@code tasks}，尤其是 {@code created_at timestamp(3)} 的毫秒精度，
 * 以覆盖「同一时间戳多条任务」这个游标最容易漏项的边界。
 */
class TaskPaginationTest {
  private static final String OWNER = "user-a";
  private static final String OTHER_OWNER = "user-b";

  private TaskRepository repository;
  private TaskService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl("jdbc:h2:mem:tasks-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        create table tasks (
          id varchar(36) primary key,
          owner_id varchar(36) not null,
          file_name varchar(512) not null,
          video_path varchar(2048) not null,
          size_bytes bigint not null,
          status varchar(32) not null,
          stage varchar(32) not null,
          progress int not null default 0,
          error_message varchar(1024),
          created_at timestamp(3) not null,
          updated_at timestamp(3) not null,
          cancelled boolean not null default false
        )
        """);
    repository = new TaskRepository(jdbc, new ObjectMapper());
    service =
        new TaskService(
            repository,
            new ObjectMapper(),
            Mockito.mock(LlmClient.class),
            new ResultValidator(),
            new WorkbenchMetrics(new SimpleMeterRegistry()),
            "target/task-pagination-storage",
            "ffmpeg",
            "http://127.0.0.1:8090",
            "test-token-test-token-test-token-test-token",
            "turbo",
            "",
            1024,
            1,
            60000,
            1);
  }

  @AfterEach
  void tearDown() {
    service.stop();
  }

  @Test
  void pagesThroughEveryTaskExactlyOnceEvenWhenTimestampsCollide() {
    Instant base = Instant.ofEpochMilli(1_700_000_000_000L);
    List<Row> rows =
        List.of(
            new Row("00000000-0000-0000-0000-000000000005", base),
            new Row("00000000-0000-0000-0000-000000000007", base),
            new Row("00000000-0000-0000-0000-000000000001", base),
            new Row("00000000-0000-0000-0000-000000000004", base.plusMillis(1)),
            new Row("00000000-0000-0000-0000-000000000002", base.plusMillis(1)),
            new Row("00000000-0000-0000-0000-000000000006", base.plusMillis(2)),
            new Row("00000000-0000-0000-0000-000000000003", base.plusMillis(3)));
    for (Row row : rows) repository.save(task(row.id(), row.createdAt()), OWNER);
    String otherOwnerTask = "00000000-0000-0000-0000-000000000099";
    repository.save(task(otherOwnerTask, base.plusMillis(10)), OTHER_OWNER);

    List<String> expected =
        rows.stream()
            .sorted(
                Comparator.comparing(Row::createdAt)
                    .reversed()
                    .thenComparing(Row::id, Comparator.reverseOrder()))
            .map(Row::id)
            .toList();

    Set<String> seen = new LinkedHashSet<>();
    List<String> order = new ArrayList<>();
    TaskCursor cursor = null;
    for (int page = 0; page < 50; page++) {
      TaskPage result = service.list(OWNER, cursor, 2);
      assertTrue(result.items().size() <= 2, "单页条目不能超过 limit");
      for (VideoTask item : result.items()) {
        assertTrue(seen.add(item.id()), "游标翻页出现重复任务：" + item.id());
        order.add(item.id());
      }
      if (result.nextCursor() == null) break;
      cursor = TaskCursor.decode(result.nextCursor());
    }

    assertEquals(rows.size(), seen.size(), "翻页取到的任务总数应与实际条数一致");
    assertEquals(expected, order, "翻页顺序应为 created_at desc, id desc");
    assertFalse(seen.contains(otherOwnerTask), "不能翻出其他账号的任务");
  }

  @Test
  void returnsNullCursorWhenEverythingFitsInOnePage() {
    insertTasks(Instant.ofEpochMilli(1_700_000_000_000L), 5);

    TaskPage result = service.list(OWNER, null, 100);

    assertEquals(5, result.items().size());
    assertNull(result.nextCursor());
  }

  @Test
  void pagesOneItemAtATimeUntilTheCursorIsExhausted() {
    insertTasks(Instant.ofEpochMilli(1_700_000_000_000L), 5);

    List<String> ids = new ArrayList<>();
    TaskCursor cursor = null;
    int pages = 0;
    for (int page = 0; page < 50; page++) {
      TaskPage result = service.list(OWNER, cursor, 1);
      assertEquals(1, result.items().size());
      ids.add(result.items().get(0).id());
      pages++;
      if (result.nextCursor() == null) break;
      cursor = TaskCursor.decode(result.nextCursor());
    }

    assertEquals(5, pages);
    assertEquals(5, new LinkedHashSet<>(ids).size());
  }

  private void insertTasks(Instant base, int count) {
    for (int index = 0; index < count; index++)
      repository.save(
          task("00000000-0000-0000-0000-00000000000" + index, base.plusMillis(index)), OWNER);
  }

  private VideoTask task(String id, Instant createdAt) {
    return new VideoTask(
        id,
        "video.mp4",
        "test://" + id,
        0,
        TaskStatus.QUEUED,
        TaskStage.IMPORT,
        0,
        null,
        createdAt,
        createdAt);
  }

  private record Row(String id, Instant createdAt) {}
}
