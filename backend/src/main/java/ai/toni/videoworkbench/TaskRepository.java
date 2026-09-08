package ai.toni.videoworkbench;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class TaskRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  TaskRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
  private final RowMapper<VideoTask> mapper = (r, n) -> map(r);
  private final RowMapper<TranscriptSegment> segmentMapper = (r, n) -> new TranscriptSegment(r.getLong("id"), r.getLong("start_ms"), r.getLong("end_ms"), r.getString("text"));
  List<VideoTask> all() { return jdbc.query("select * from tasks order by created_at desc", mapper); }
  List<VideoTask> recoverable() { return jdbc.query("select * from tasks where cancelled=false and status in ('QUEUED','PROCESSING') order by created_at", mapper); }
  Optional<VideoTask> find(String id) { return jdbc.query("select * from tasks where id=?", mapper, id).stream().findFirst(); }
  void save(VideoTask t) { jdbc.update("insert into tasks(id,file_name,video_path,size_bytes,status,stage,progress,error_message,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)", t.id(),t.fileName(),t.videoPath(),t.sizeBytes(),t.status().name(),t.stage().name(),t.progress(),t.errorMessage(),Timestamp.from(t.createdAt()),Timestamp.from(t.updatedAt())); }
  void update(String id, TaskStatus s, TaskStage stage, int progress, String error) { jdbc.update("update tasks set status=?,stage=?,progress=?,error_message=?,updated_at=? where id=?",s.name(),stage.name(),progress,error,Timestamp.from(Instant.now()),id); }
  void cancel(String id) { jdbc.update("update tasks set cancelled=true,status='CANCELLED',updated_at=? where id=?",Timestamp.from(Instant.now()),id); }
  void reset(String id, TaskStage stage) { jdbc.update("update tasks set cancelled=false,status='QUEUED',stage=?,progress=0,error_message=null,updated_at=? where id=?",stage.name(),Timestamp.from(Instant.now()),id); }
  void markProcessingAsQueued() { jdbc.update("update tasks set status='QUEUED',updated_at=? where status='PROCESSING' and cancelled=false", Timestamp.from(Instant.now())); }
  void replaceSegments(String taskId, List<TranscriptSegment> segments) { jdbc.update("delete from transcript_segments where task_id=?", taskId); for (TranscriptSegment segment : segments) jdbc.update("insert into transcript_segments(task_id,start_ms,end_ms,text) values(?,?,?,?)", taskId,segment.startMs(),segment.endMs(),segment.text()); }
  List<TranscriptSegment> segments(String taskId) { return jdbc.query("select * from transcript_segments where task_id=? order by start_ms", segmentMapper, taskId); }
  void saveResult(String id, TaskResult result) { try { jdbc.update("insert into task_results(task_id,summary,key_points_json,chapters_json) values(?,?,cast(? as json),cast(? as json)) on duplicate key update summary=values(summary),key_points_json=values(key_points_json),chapters_json=values(chapters_json)", id,result.summary(),json.writeValueAsString(result.keyPoints()),json.writeValueAsString(result.chapters())); } catch (Exception ex) { throw new IllegalStateException("无法保存内容结果", ex); } }
  Optional<TaskResult> result(String id) { return jdbc.query("select * from task_results where task_id=?", (r,n) -> { try { return new TaskResult(r.getString("summary"),json.readValue(r.getString("key_points_json"),new TypeReference<List<String>>() {}),json.readValue(r.getString("chapters_json"),new TypeReference<List<Chapter>>() {})); } catch (Exception ex) { throw new SQLException(ex); } }, id).stream().findFirst(); }
  void delete(String id) { jdbc.update("delete from transcript_segments where task_id=?",id); jdbc.update("delete from task_results where task_id=?",id); jdbc.update("delete from tasks where id=?",id); }
  boolean cancelled(String id) { return Boolean.TRUE.equals(jdbc.queryForObject("select cancelled from tasks where id=?", Boolean.class,id)); }
  private VideoTask map(ResultSet r) throws SQLException { return new VideoTask(r.getString("id"),r.getString("file_name"),r.getString("video_path"),r.getLong("size_bytes"),TaskStatus.valueOf(r.getString("status")),TaskStage.valueOf(r.getString("stage")),r.getInt("progress"),r.getString("error_message"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant()); }
}
