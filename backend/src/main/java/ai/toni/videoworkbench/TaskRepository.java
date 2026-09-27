package ai.toni.videoworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class TaskRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  TaskRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<VideoTask> mapper = (r, n) -> map(r);
  private final RowMapper<TranscriptSegment> segmentMapper =
      (r, n) ->
          new TranscriptSegment(
              r.getLong("id"),
              r.getLong("start_ms"),
              r.getLong("end_ms"),
              r.getString("text"),
              r.getString("translation"));

  /**
   * 按 {@code (created_at desc, id desc)} 取一页任务。{@code cursor} 为空表示第一页；否则用 {@code (created_at, id)}
   * 元组做键集比较，避免 OFFSET 在大列表上的性能与漂移问题。调用方多取 一条以判断是否还有下一页。
   */
  List<VideoTask> page(String ownerId, TaskCursor cursor, int limit) {
    if (cursor == null)
      return jdbc.query(
          "select * from tasks where owner_id=? order by created_at desc,id desc limit ?",
          mapper,
          ownerId,
          limit);
    Timestamp at = Timestamp.from(cursor.createdAt());
    return jdbc.query(
        "select * from tasks where owner_id=? and (created_at<? or (created_at=? and id<?)) order by created_at desc,id desc limit ?",
        mapper,
        ownerId,
        at,
        at,
        cursor.id(),
        limit);
  }

  List<VideoTask> recoverable() {
    return jdbc.query(
        "select * from tasks where cancelled=false and status in ('QUEUED','PROCESSING') order by created_at",
        mapper);
  }

  /** 后台处理线程按任务号取数，不做归属过滤；对外读写一律走 {@link #findOwned}。 */
  Optional<VideoTask> find(String id) {
    return jdbc.query("select * from tasks where id=?", mapper, id).stream().findFirst();
  }

  Optional<VideoTask> findOwned(String id, String ownerId) {
    return jdbc.query("select * from tasks where id=? and owner_id=?", mapper, id, ownerId).stream()
        .findFirst();
  }

  void save(VideoTask t, String ownerId) {
    jdbc.update(
        "insert into tasks(id,owner_id,file_name,video_path,size_bytes,status,stage,progress,error_message,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
        t.id(),
        ownerId,
        t.fileName(),
        t.videoPath(),
        t.sizeBytes(),
        t.status().name(),
        t.stage().name(),
        t.progress(),
        t.errorMessage(),
        Timestamp.from(t.createdAt()),
        Timestamp.from(t.updatedAt()));
  }

  void update(String id, TaskStatus s, TaskStage stage, int progress, String error) {
    jdbc.update(
        "update tasks set status=?,stage=?,progress=?,error_message=?,updated_at=? where id=?",
        s.name(),
        stage.name(),
        progress,
        error,
        Timestamp.from(Instant.now()),
        id);
  }

  boolean cancel(String id) {
    return jdbc.update(
            "update tasks set cancelled=true,status='CANCELLED',updated_at=? where id=? and status in ('QUEUED','PROCESSING')",
            Timestamp.from(Instant.now()),
            id)
        == 1;
  }

  void reset(String id, TaskStage stage) {
    jdbc.update(
        "update tasks set cancelled=false,status='QUEUED',stage=?,progress=0,error_message=null,updated_at=? where id=?",
        stage.name(),
        Timestamp.from(Instant.now()),
        id);
  }

  boolean claimForProcessing(String id) {
    return jdbc.update(
            "update tasks set status='PROCESSING',updated_at=? where id=? and status='QUEUED' and cancelled=false",
            Timestamp.from(Instant.now()),
            id)
        == 1;
  }

  void markProcessingAsQueued() {
    jdbc.update(
        "update tasks set status='QUEUED',updated_at=? where status='PROCESSING' and cancelled=false",
        Timestamp.from(Instant.now()));
  }

  @Transactional
  void replaceSegments(String taskId, List<TranscriptSegment> segments) {
    jdbc.update("delete from transcript_segments where task_id=?", taskId);
    for (TranscriptSegment segment : segments)
      jdbc.update(
          "insert into transcript_segments(task_id,start_ms,end_ms,text,translation) values(?,?,?,?,?)",
          taskId,
          segment.startMs(),
          segment.endMs(),
          segment.text(),
          segment.translation());
    // A summary refers to the previous transcript version and must never be shown with new
    // segments.
    jdbc.update("delete from task_results where task_id=?", taskId);
  }

  void deleteResult(String taskId) {
    jdbc.update("delete from task_results where task_id=?", taskId);
  }

  void replaceTranslations(String taskId, List<TranscriptSegment> segments) {
    for (TranscriptSegment segment : segments)
      jdbc.update(
          "update transcript_segments set translation=? where task_id=? and id=?",
          segment.translation(),
          taskId,
          segment.id());
  }

  List<TranscriptSegment> segments(String taskId) {
    return jdbc.query(
        "select * from transcript_segments where task_id=? order by start_ms",
        segmentMapper,
        taskId);
  }

  void saveResult(String id, TaskResult result) {
    try {
      jdbc.update(
          "insert into task_results(task_id,summary,key_points_json,chapters_json) values(?,?,cast(? as json),cast(? as json)) on duplicate key update summary=values(summary),key_points_json=values(key_points_json),chapters_json=values(chapters_json)",
          id,
          result.summary(),
          json.writeValueAsString(result.keyPoints()),
          json.writeValueAsString(result.chapters()));
    } catch (Exception ex) {
      throw new IllegalStateException("无法保存内容结果", ex);
    }
  }

  Optional<TaskResult> result(String id) {
    return jdbc
        .query(
            "select * from task_results where task_id=?",
            (r, n) -> {
              try {
                return new TaskResult(
                    r.getString("summary"),
                    json.readValue(
                        r.getString("key_points_json"), new TypeReference<List<String>>() {}),
                    json.readValue(
                        r.getString("chapters_json"), new TypeReference<List<Chapter>>() {}));
              } catch (Exception ex) {
                throw new SQLException(ex);
              }
            },
            id)
        .stream()
        .findFirst();
  }

  void delete(String id) {
    jdbc.update("delete from transcript_segments where task_id=?", id);
    jdbc.update("delete from task_results where task_id=?", id);
    jdbc.update("delete from tasks where id=?", id);
  }

  boolean cancelled(String id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("select cancelled from tasks where id=?", Boolean.class, id));
  }

  private VideoTask map(ResultSet r) throws SQLException {
    return new VideoTask(
        r.getString("id"),
        r.getString("file_name"),
        r.getString("video_path"),
        r.getLong("size_bytes"),
        TaskStatus.valueOf(r.getString("status")),
        TaskStage.valueOf(r.getString("stage")),
        r.getInt("progress"),
        r.getString("error_message"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant());
  }
}
