package ai.toni.videoworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
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
  private final TaskEventStream events;

  TaskRepository(JdbcTemplate jdbc, ObjectMapper json, TaskEventStream events) {
    this.jdbc = jdbc;
    this.json = json;
    this.events = events;
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

  /**
   * 启动恢复用：状态是 {@code QUEUED} 但不确定消息是否还在队列里的任务，需要重投一遍。
   *
   * <p>只取 {@code QUEUED}：{@code PROCESSING} 的行归属某个持有者的租约，租约没过期就说明它的主人正拿着消息在跑， 重投只会被领取条件挡掉；租约确实过期时由
   * {@link TaskLease} 的巡检负责回收与重投。
   */
  List<VideoTask> queued() {
    return jdbc.query(
        "select * from tasks where cancelled=false and status='QUEUED' order by created_at",
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
        "insert into tasks(id,owner_id,file_name,display_name,video_path,size_bytes,status,stage,progress,error_message,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
        t.id(),
        ownerId,
        t.fileName(),
        t.displayName(),
        t.videoPath(),
        t.sizeBytes(),
        t.status().name(),
        t.stage().name(),
        t.progress(),
        t.errorMessage(),
        Timestamp.from(t.createdAt()),
        Timestamp.from(t.updatedAt()));
    events.taskChanged(t.id(), ownerId);
  }

  /** 改名只更新展示名，不动 updated_at：改名不是处理进度，不应影响相对时间展示。 */
  boolean rename(String id, String displayName) {
    boolean renamed =
        jdbc.update("update tasks set display_name=? where id=?", displayName, id) == 1;
    if (renamed) events.taskChanged(id);
    return renamed;
  }

  void update(String id, TaskStatus s, TaskStage stage, int progress, String error) {
    // 租约只在 PROCESSING 期间存在：任何离开 PROCESSING 的状态写入都顺手清掉持有者与租约，
    // 免得一条已经 FAILED 的任务上还挂着上一任持有者的租约，排查时产生误导。
    boolean holding = s == TaskStatus.PROCESSING;
    int updated =
        jdbc.update(
            "update tasks set status=?,stage=?,progress=?,error_message=?,updated_at=?"
                + (holding ? "" : ",locked_by=null,lease_expires_at=null")
                + " where id=?",
            s.name(),
            stage.name(),
            progress,
            error,
            Timestamp.from(Instant.now()),
            id);
    if (updated > 0) events.taskChanged(id);
  }

  boolean cancel(String id) {
    boolean cancelled =
        jdbc.update(
                "update tasks set cancelled=true,status='CANCELLED',updated_at=? where id=? and status in ('QUEUED','PROCESSING')",
                Timestamp.from(Instant.now()),
                id)
            == 1;
    if (cancelled) events.taskChanged(id);
    return cancelled;
  }

  void reset(String id, TaskStage stage) {
    jdbc.update(
        "update tasks set cancelled=false,status='QUEUED',stage=?,progress=0,error_message=null,updated_at=? where id=?",
        stage.name(),
        Timestamp.from(Instant.now()),
        id);
    events.taskChanged(id);
  }

  /**
   * 领取任务：把 {@code QUEUED} 原子改成 {@code PROCESSING}，同时记下持有者与租约到期时间。
   *
   * <p>条件更新是幂等的唯一裁决点——多个实例（或多个消费者线程）同时投递同一条消息时，只有一个能领到。 租约只是让其它实例知道「这条 PROCESSING
   * 有人在跑、什么时候算失联」，不参与幂等裁决。
   */
  boolean claimForProcessing(String id, String instanceId, Instant leaseExpiresAt) {
    boolean claimed =
        jdbc.update(
                "update tasks set status='PROCESSING',locked_by=?,lease_expires_at=?,updated_at=? where id=? and status='QUEUED' and cancelled=false",
                instanceId,
                Timestamp.from(leaseExpiresAt),
                Timestamp.from(Instant.now()),
                id)
            == 1;
    if (claimed) events.taskChanged(id);
    return claimed;
  }

  /**
   * 续租：只续本实例真正在处理的任务。
   *
   * <p>按任务号逐个续而不是「把自己名下所有 PROCESSING 都续一遍」：实例可能因为数据库故障把某个任务放成了 「仍标着 PROCESSING
   * 但已经不再处理」的状态，若按实例整批续租，这条永远不会过期，任务就卡死了。
   *
   * @return 续上的行数；0 说明租约已被回收或已不属于本实例
   */
  int renewLeases(Collection<String> taskIds, String instanceId, Instant leaseExpiresAt) {
    int renewed = 0;
    for (String id : taskIds)
      renewed +=
          jdbc.update(
              "update tasks set lease_expires_at=? where id=? and locked_by=? and status='PROCESSING'",
              Timestamp.from(leaseExpiresAt),
              id,
              instanceId);
    return renewed;
  }

  /**
   * 租约已过期（含升级前遗留、从未写过租约）却仍标着 {@code PROCESSING} 的任务号。
   *
   * <p>这些行的持有者多半已经不在了，需要回收重投，否则它们会永远卡在「处理中」。
   */
  List<String> expiredClaims(Instant now) {
    return jdbc.queryForList(
        "select id from tasks where cancelled=false and status='PROCESSING' and (lease_expires_at is null or lease_expires_at<?)",
        String.class,
        Timestamp.from(now));
  }

  /**
   * 回收一条过期租约，把它放回 {@code QUEUED} 等待重投。
   *
   * <p>条件里再校验一次租约确已过期：多个实例同时巡检时，只有一个能把行改成 QUEUED，避免重复回收与重复投递。
   */
  boolean releaseExpiredClaim(String id, Instant now) {
    Timestamp at = Timestamp.from(now);
    boolean released =
        jdbc.update(
                "update tasks set status='QUEUED',locked_by=null,lease_expires_at=null,updated_at=? where id=? and status='PROCESSING' and (lease_expires_at is null or lease_expires_at<?)",
                at,
                id,
                at)
            == 1;
    if (released) events.taskChanged(id);
    return released;
  }

  /**
   * 优雅停机：把自己持有的任务立刻放回 {@code QUEUED}，别的实例可以马上接手，不必等租约过期。
   *
   * @return 释放的行数
   */
  int releaseClaimsOwnedBy(String instanceId) {
    return jdbc.update(
        "update tasks set status='QUEUED',locked_by=null,lease_expires_at=null,updated_at=? where status='PROCESSING' and locked_by=?",
        Timestamp.from(Instant.now()),
        instanceId);
  }

  /**
   * 把卡在 {@code PROCESSING} 的任务放回 {@code QUEUED}。
   *
   * <p>用于重试投递：上一次尝试被基础设施故障（数据库短暂不可达等）打断时任务仍是 {@code PROCESSING}，
   * 不放回去这次重试就领不到它。但只有「租约属于本实例」或「租约已过期」时才放——否则就是别的实例正在处理的任务， 抢回来会造成重复执行。
   */
  boolean releaseProcessing(String id, String instanceId, Instant now) {
    Timestamp at = Timestamp.from(now);
    boolean released =
        jdbc.update(
                "update tasks set status='QUEUED',locked_by=null,lease_expires_at=null,updated_at=? where id=? and status='PROCESSING' and (locked_by=? or lease_expires_at is null or lease_expires_at<?)",
                at,
                id,
                instanceId,
                at)
            == 1;
    if (released) events.taskChanged(id);
    return released;
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

  /** 删除任务。归属人由调用方传入：删完这一行就查不到 owner 了，而界面（包括同账号的其它页面） 仍需要收到一次「这条任务没了」的通知。 */
  void delete(String id, String ownerId) {
    jdbc.update("delete from transcript_segments where task_id=?", id);
    jdbc.update("delete from task_results where task_id=?", id);
    jdbc.update("delete from tasks where id=?", id);
    events.taskChanged(id, ownerId);
  }

  boolean cancelled(String id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("select cancelled from tasks where id=?", Boolean.class, id));
  }

  private VideoTask map(ResultSet r) throws SQLException {
    return new VideoTask(
        r.getString("id"),
        r.getString("file_name"),
        r.getString("display_name"),
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
