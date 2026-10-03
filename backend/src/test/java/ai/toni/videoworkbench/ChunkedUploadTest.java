package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

/**
 * 分片上传的实测：乱序分片要能拼回原文件、缺片必须拒绝、跨账号必须 403、重新选同一个文件要能从缺的那片继续。
 *
 * <p>用真实的文件系统（{@link TempDir}）而不是内存实现，因为这一版把上传进度就存在文件系统里—— 「进程重启后进度还在」这件事只有落到文件上才算真的验证过。
 */
class ChunkedUploadTest {
  private static final String OWNER = "user-a";
  private static final int CHUNK = ChunkedUploadService.CHUNK_SIZE;

  @TempDir Path storage;

  private TaskRepository repository;
  private ChunkedUploadService uploads;
  private TaskService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl("jdbc:h2:mem:uploads-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
    TaskQueue queue = Mockito.mock(TaskQueue.class);
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
            "test-token-test-token-test-token-test-token",
            "turbo",
            "",
            20L * CHUNK,
            1,
            60000,
            queue,
            new TaskLease(repository, queue, metrics, "test-instance", 90),
            DownstreamResilience.withDefaults());
    uploads = new ChunkedUploadService(service, storage.toString(), 20L * CHUNK);
  }

  @AfterEach
  void tearDown() {
    service.stop();
  }

  @Test
  void resumesFromMissingChunksAndRegistersTheAssembledVideo() {
    byte[] first = chunk(0x11, CHUNK);
    byte[] last = {0x22, 0x33, 0x44};
    long size = (long) CHUNK + last.length;

    ChunkedUploadService.Session started = uploads.init("长视频.mp4", size, OWNER);
    assertTrue(started.receivedChunks().isEmpty(), "新会话不应有任何分片");
    assertEquals(CHUNK, started.chunkSize());

    // 只传了第一片就「断网」：这时完成上传必须被拒绝，不能登记一个不完整的视频。
    uploads.putChunk(started.uploadId(), OWNER, 0, first);
    assertEquals(List.of(0), uploads.status(started.uploadId(), OWNER).receivedChunks());
    assertThrows(ResponseStatusException.class, () -> uploads.complete(started.uploadId(), OWNER));

    // 重新选择同一个文件：init 会给出同一个 uploadId，已收的分片还在，只差最后一片。
    ChunkedUploadService.Session resumed = uploads.init("长视频.mp4", size, OWNER);
    assertEquals(started.uploadId(), resumed.uploadId());
    assertEquals(List.of(0), resumed.receivedChunks());

    // 补上缺的那一片再完成：乱序写入也要拼回原文件。
    uploads.putChunk(resumed.uploadId(), OWNER, 1, last);
    VideoTask task = uploads.complete(resumed.uploadId(), OWNER);

    byte[] expected = new byte[(int) size];
    System.arraycopy(first, 0, expected, 0, first.length);
    System.arraycopy(last, 0, expected, CHUNK, last.length);
    byte[] written = read(task.videoPath());
    assertArrayEquals(expected, written, "拼回来的文件必须与分片按序拼接一致");
    assertEquals(size, task.sizeBytes());
    assertEquals("长视频.mp4", task.fileName());
    assertEquals(TaskStatus.QUEUED, task.status());
    assertTrue(repository.find(task.id()).isPresent(), "完成的视频必须登记成任务");

    assertThrows(
        ResponseStatusException.class,
        () -> uploads.status(resumed.uploadId(), OWNER),
        "会话目录应在完成后清掉");
  }

  @Test
  void rejectsChunksOutsideTheDeclaredSize() {
    ChunkedUploadService.Session started = uploads.init("clip.mp4", CHUNK + 10L, OWNER);

    assertThrows(
        ResponseStatusException.class,
        () -> uploads.putChunk(started.uploadId(), OWNER, 2, chunk(0x33, 10)),
        "序号超过分片数应被拒绝");
    assertThrows(
        ResponseStatusException.class,
        () -> uploads.putChunk(started.uploadId(), OWNER, 1, chunk(0x44, 11)),
        "最后一片不能超过声明的大小");
    assertThrows(
        ResponseStatusException.class,
        () -> uploads.putChunk(started.uploadId(), OWNER, 0, chunk(0x55, CHUNK + 1)),
        "单片不能超过分片大小");
    assertThrows(
        ResponseStatusException.class,
        () -> uploads.putChunk(started.uploadId(), OWNER, 0, new byte[0]),
        "空分片应被拒绝");
  }

  @Test
  void refusesAnotherOwnersSession() {
    ChunkedUploadService.Session started = uploads.init("clip.mp4", 1024, OWNER);
    uploads.putChunk(started.uploadId(), OWNER, 0, chunk(0x66, 1024));

    assertThrows(AccessDeniedException.class, () -> uploads.status(started.uploadId(), "user-b"));
    assertThrows(
        AccessDeniedException.class,
        () -> uploads.putChunk(started.uploadId(), "user-b", 0, chunk(0x77, 1024)));
    assertThrows(AccessDeniedException.class, () -> uploads.complete(started.uploadId(), "user-b"));
  }

  @Test
  void rejectsSessionsThatDeclareAnImpossibleFile() {
    assertThrows(
        ResponseStatusException.class, () -> uploads.init("notes.txt", 1024, OWNER), "非视频扩展名应被拒绝");
    assertThrows(
        ResponseStatusException.class, () -> uploads.init("clip.mp4", 0, OWNER), "零字节应被拒绝");
    assertThrows(
        ResponseStatusException.class,
        () -> uploads.init("clip.mp4", 21L * CHUNK, OWNER),
        "超过上限应被拒绝");
    assertThrows(
        ResponseStatusException.class, () -> uploads.status("不存在的会话", OWNER), "不存在的会话应 404");
  }

  private static byte[] chunk(int value, int length) {
    byte[] data = new byte[length];
    java.util.Arrays.fill(data, (byte) value);
    return data;
  }

  private static byte[] read(String path) {
    try {
      return Files.readAllBytes(Path.of(path));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
