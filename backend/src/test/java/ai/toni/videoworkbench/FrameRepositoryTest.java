package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 帧落库与 OCR 文本回填：全量替换语义（重跑视觉阶段先清旧行）、按时间升序读取、 只取有文本的帧进摘要上下文。表结构与 V8 迁移对齐。 */
class FrameRepositoryTest {
  private JdbcTemplate jdbc;
  private FrameRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl("jdbc:h2:mem:frames-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        create table video_frames (
          id bigint auto_increment primary key,
          task_id varchar(36) not null,
          ts_ms bigint not null,
          ocr_text varchar(4096),
          phash bigint not null,
          path varchar(512) not null,
          constraint uk_video_frames_task_ts unique (task_id, ts_ms)
        )
        """);
    repository = new FrameRepository(jdbc);
  }

  @Test
  void replaceWipesOldRowsAndKeepsAscendingOrder() {
    repository.replace(
        "task-1",
        List.of(
            new FrameRepository.VideoFrame(2_000, null, 11L, "a.jpg"),
            new FrameRepository.VideoFrame(1_000, null, 22L, "b.jpg")));
    // 重跑：替换语义，旧行不能残留。
    repository.replace(
        "task-1", List.of(new FrameRepository.VideoFrame(3_000, null, 33L, "c.jpg")));

    List<FrameRepository.VideoFrame> frames = repository.frames("task-1");
    assertEquals(1, frames.size(), "replace 必须清掉上一轮的帧");
    assertEquals(3_000, frames.getFirst().tsMs());
    assertEquals(33L, frames.getFirst().phash());
  }

  @Test
  void ocrTextBackfillUpdatesOnlyRecognizedFrames() {
    repository.replace(
        "task-1",
        List.of(
            new FrameRepository.VideoFrame(1_000, null, 1L, "a.jpg"),
            new FrameRepository.VideoFrame(2_000, null, 2L, "b.jpg")));
    repository.updateOcrText(
        "task-1", List.of(new FrameRepository.VideoFrame(1_000, "板书内容", 1L, "a.jpg")));

    List<FrameRepository.VideoFrame> frames = repository.frames("task-1");
    assertEquals("板书内容", frames.get(0).ocrText());
    assertEquals(null, frames.get(1).ocrText(), "未被识别的帧保持空文本");

    List<FrameText> texts = repository.frameTexts("task-1");
    assertEquals(1, texts.size(), "只有有文本的帧进入摘要上下文");
    assertEquals("板书内容", texts.getFirst().text());
    assertTrue(texts.getFirst().tsMs() == 1_000);
  }
}
