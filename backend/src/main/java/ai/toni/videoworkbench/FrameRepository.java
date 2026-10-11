package ai.toni.videoworkbench;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 关键帧与 OCR 文本落库。帧随任务级联删除（V8 外键），OCR 文本由异步任务在转写期间回填。 */
@Repository
class FrameRepository {
  private final JdbcTemplate jdbc;

  FrameRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<VideoFrame> mapper =
      (r, n) ->
          new VideoFrame(
              r.getLong("ts_ms"), r.getString("ocr_text"), r.getLong("phash"), r.getString("path"));

  /** 全量替换某任务的关键帧（重跑视觉阶段前先清旧行，语义与 replaceSegments 一致）。 */
  void replace(String taskId, List<VideoFrame> frames) {
    jdbc.update("delete from video_frames where task_id=?", taskId);
    for (VideoFrame frame : frames)
      jdbc.update(
          "insert into video_frames(task_id,ts_ms,ocr_text,phash,path) values(?,?,null,?,?)",
          taskId,
          frame.tsMs(),
          frame.phash(),
          frame.path());
  }

  List<VideoFrame> frames(String taskId) {
    return jdbc.query(
        "select ts_ms, ocr_text, phash, path from video_frames where task_id=? order by ts_ms",
        mapper,
        taskId);
  }

  /** 回填一批 OCR 文本（逐帧更新即可，单任务帧数有上限）。 */
  void updateOcrText(String taskId, List<VideoFrame> frames) {
    for (VideoFrame frame : frames)
      jdbc.update(
          "update video_frames set ocr_text=? where task_id=? and ts_ms=?",
          frame.ocrText(),
          taskId,
          frame.tsMs());
  }

  /** 有可识别画面文本的帧；无帧文本时摘要不附加画面上下文。 */
  List<FrameText> frameTexts(String taskId) {
    return jdbc
        .query(
            "select ts_ms, ocr_text from video_frames where task_id=? and ocr_text is not null and ocr_text<>'' order by ts_ms",
            (r, n) -> new FrameText(r.getLong("ts_ms"), r.getString("ocr_text")),
            taskId)
        .stream()
        .toList();
  }

  /** 帧图与帧行必须在重跑前一起清掉，否则旧行会指向被删除或被覆盖的文件。 */
  void delete(String taskId) {
    jdbc.update("delete from video_frames where task_id=?", taskId);
  }

  record VideoFrame(long tsMs, String ocrText, long phash, String path) {}
}
