package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;

class TaskRepositoryTest {
  private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
  private final TaskRepository repository =
      new TaskRepository(jdbc, new ObjectMapper(), new TaskEventStream(jdbc));

  @Test
  void filtersTaskListByOwner() {
    when(jdbc.query(any(String.class), any(RowMapper.class), eq("user-a"), eq(21)))
        .thenReturn(List.of());

    assertTrue(repository.page("user-a", null, 21).isEmpty());
    verify(jdbc).query(contains("owner_id=?"), any(RowMapper.class), eq("user-a"), eq(21));
  }

  @Test
  void pagesByKeysetTupleWhenCursorIsPresent() {
    TaskCursor cursor = new TaskCursor(Instant.ofEpochMilli(1_700_000_000_123L), "task-1");
    when(jdbc.query(
            any(String.class),
            any(RowMapper.class),
            eq("user-a"),
            any(),
            any(),
            eq("task-1"),
            eq(5)))
        .thenReturn(List.of());

    assertTrue(repository.page("user-a", cursor, 5).isEmpty());
    verify(jdbc)
        .query(
            contains("created_at<? or (created_at=? and id<?)"),
            any(RowMapper.class),
            eq("user-a"),
            any(),
            any(),
            eq("task-1"),
            eq(5));
  }

  @Test
  void requiresOwnerWhenLookingUpTask() {
    when(jdbc.query(any(String.class), any(RowMapper.class), eq("task-1"), eq("user-a")))
        .thenReturn(List.of());

    assertTrue(repository.findOwned("task-1", "user-a").isEmpty());
    verify(jdbc)
        .query(contains("id=? and owner_id=?"), any(RowMapper.class), eq("task-1"), eq("user-a"));
  }

  @Test
  void storesOwnerWhenSavingTask() {
    Instant now = Instant.now();
    repository.save(
        new VideoTask(
            "task-1",
            "video.mp4",
            null,
            "video.mp4",
            1,
            TaskStatus.QUEUED,
            TaskStage.IMPORT,
            0,
            null,
            now,
            now),
        "user-a");

    verify(jdbc)
        .update(
            contains("insert into tasks(id,owner_id"),
            eq("task-1"),
            eq("user-a"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void claimsOnlyQueuedUncancelledTask() {
    when(jdbc.update(any(String.class), any(), any(), any(), eq("task-1"))).thenReturn(1);

    assertTrue(
        repository.claimForProcessing("task-1", "instance-a", Instant.now().plusSeconds(90)));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("status='QUEUED' and cancelled=false"),
            eq("instance-a"),
            any(),
            any(),
            eq("task-1"));
  }

  @Test
  void cancelsOnlyQueuedOrProcessingTask() {
    when(jdbc.update(any(String.class), any(), eq("task-1"))).thenReturn(1);

    assertTrue(repository.cancel("task-1"));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("status in ('QUEUED','PROCESSING')"),
            any(),
            eq("task-1"));
  }

  @Test
  void replacesTranscriptAndInvalidatesItsPreviousResultInOneTransaction() throws Exception {
    repository.replaceSegments("task-1", List.of(new TranscriptSegment(0, 0, 1000, "新的转写", null)));

    InOrder ordered = inOrder(jdbc);
    ordered.verify(jdbc).update("delete from transcript_segments where task_id=?", "task-1");
    ordered
        .verify(jdbc)
        .update(
            any(String.class),
            eq("task-1"),
            eq(0L),
            eq(1000L),
            eq("新的转写"),
            eq(null),
            eq(null),
            eq(null));
    ordered.verify(jdbc).update("delete from task_results where task_id=?", "task-1");
    Method method =
        TaskRepository.class.getDeclaredMethod("replaceSegments", String.class, List.class);
    assertTrue(method.isAnnotationPresent(Transactional.class));
  }

  /**
   * 用内存 H2 跑真实的 segmentMapper 读取路径：证明 {@code speaker} 与 {@code words}(JSON) 列能被正确解析回 {@link
   * TranscriptSegment} / {@link TranscriptWord}，这是 mock 测试覆盖不到、MySQL 集成测试在本机被跳过的部分。
   */
  @Test
  void readsSpeakerAndWordsFromColumns() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl("jdbc:h2:mem:segments-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "create table transcript_segments ("
            + "id bigint primary key auto_increment, task_id varchar(36) not null, "
            + "start_ms bigint not null, end_ms bigint not null, text varchar not null, "
            + "translation varchar, speaker varchar(64), words varchar)");
    TaskRepository real = new TaskRepository(jdbc, new ObjectMapper(), new TaskEventStream(jdbc));
    jdbc.update(
        "insert into transcript_segments(task_id,start_ms,end_ms,text,translation,speaker,words) "
            + "values(?,?,?,?,?,?,?)",
        "task-1",
        0L,
        1000L,
        "你好世界",
        null,
        "SPEAKER_00",
        "[{\"startMs\":0,\"endMs\":500,\"text\":\"你好\",\"speaker\":\"SPEAKER_00\"},"
            + "{\"startMs\":500,\"endMs\":1000,\"text\":\"世界\"}]");

    List<TranscriptSegment> segments = real.segments("task-1");
    assertEquals(1, segments.size());
    TranscriptSegment seg = segments.get(0);
    assertEquals("SPEAKER_00", seg.speaker());
    assertEquals(2, seg.words().size());
    assertEquals("你好", seg.words().get(0).text());
    assertEquals(0L, seg.words().get(0).startMs());
    assertEquals("SPEAKER_00", seg.words().get(0).speaker());
    assertEquals("世界", seg.words().get(1).text());
    assertEquals(1000L, seg.words().get(1).endMs());
  }
}
