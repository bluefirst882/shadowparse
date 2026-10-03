package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/** 任务改名的行为边界：自己的任务能改名且只动展示名（原文件名保留，导出与溯源仍用它）； 他人任务 403；空名与超长名 400。 */
class TaskRenameTest {
  private static final String OWNER = "user-a";
  private static final String OTHER = "user-b";

  private @TempDir Path storage;

  private TaskRepository repository;
  private TaskService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl("jdbc:h2:mem:rename-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        create table tasks (
          id varchar(36) primary key,
          owner_id varchar(36) not null,
          file_name varchar(512) not null,
          display_name varchar(200),
          video_path varchar(2048) not null,
          size_bytes bigint not null,
          status varchar(32) not null,
          stage varchar(32) not null,
          progress int not null default 0,
          error_message varchar(1024),
          created_at timestamp(3) not null,
          updated_at timestamp(3) not null,
          cancelled boolean not null default false,
          locked_by varchar(64),
          lease_expires_at timestamp(3)
        )
        """);
    repository = new TaskRepository(jdbc, new ObjectMapper(), new TaskEventStream(jdbc));
    WorkbenchMetrics metrics = new WorkbenchMetrics(new SimpleMeterRegistry());
    service =
        new TaskService(
            repository,
            new ObjectMapper(),
            Mockito.mock(LlmClient.class),
            new ResultValidator(),
            metrics,
            storage.toString(),
            "ffmpeg",
            "http://127.0.0.1:8090",
            "test-token-test-token-test-token-test-token-test-token",
            "turbo",
            "",
            1024 * 1024L,
            1,
            60000,
            Mockito.mock(TaskQueue.class),
            new TaskLease(repository, Mockito.mock(TaskQueue.class), metrics, "test-instance", 90),
            DownstreamResilience.withDefaults());
  }

  @AfterEach
  void tearDown() {
    service.stop();
  }

  private String seedTask(String ownerId) {
    Instant now = Instant.now();
    VideoTask task =
        new VideoTask(
            UUID.randomUUID().toString(),
            "interview.mp4",
            null,
            storage.resolve("interview.mp4").toString(),
            1024,
            TaskStatus.COMPLETED,
            TaskStage.COMPLETED,
            100,
            null,
            now,
            now);
    repository.save(task, ownerId);
    return task.id();
  }

  @Test
  void renamesOwnTaskAndKeepsFileName() {
    String id = seedTask(OWNER);

    VideoTask renamed = service.rename(id, OWNER, "  Spirit 赛后采访  ");

    assertEquals("Spirit 赛后采访", renamed.displayName(), "展示名应去空白后保存");
    assertEquals("interview.mp4", renamed.fileName(), "原文件名必须保留");
    assertEquals("Spirit 赛后采访", repository.findOwned(id, OWNER).orElseThrow().displayName());
    // 改名不是处理进度：updated_at 不应被刷新。
    assertEquals(
        repository.find(id).orElseThrow().createdAt(),
        repository.find(id).orElseThrow().updatedAt(),
        "种子任务的 created/updated 相同，改名后仍应保持相同");
  }

  @Test
  void rejectsRenamingAnotherUsersTask() {
    String id = seedTask(OWNER);
    assertThrows(AccessDeniedException.class, () -> service.rename(id, OTHER, "别人的任务"));
    assertEquals(null, repository.findOwned(id, OWNER).orElseThrow().displayName());
  }

  @Test
  void rejectsBlankName() {
    String id = seedTask(OWNER);
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.rename(id, OWNER, "   "));
    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void rejectsNameLongerThan100Chars() {
    String id = seedTask(OWNER);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.rename(id, OWNER, "长".repeat(101)));
    assertEquals(400, ex.getStatusCode().value());
  }
}
