package ai.toni.videoworkbench;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务认领的租约（多实例安全）。
 *
 * <p>原来的方案是「状态即锁」：领取就是把 {@code QUEUED} 原子改成 {@code PROCESSING}。这在单实例下没问题，
 * 多实例下有两个漏洞——启动恢复会把别的实例正在处理的行也改回 {@code QUEUED}，以及无法区分「别的实例在跑」与
 * 「上一任持有者失联了」，结果是同一任务被两个实例同时执行。所以领取时额外写下持有者与租约到期时间：
 *
 * <ul>
 *   <li><b>领取</b>仍是同一条条件更新（{@code status='QUEUED'}），只是连持有者一起写；幂等裁决点没变。
 *   <li><b>心跳</b>只续本实例真正在跑的任务：实例还活着但任务很慢（长视频转写）时不会被误判成失联。
 *   <li><b>回收</b>只碰租约已过期的行，碰不到别人正在跑的任务。
 * </ul>
 *
 * <p>三种恢复路径互补：优雅停机时 {@link #releaseOwned()} 立刻交还；进程崩溃（SIGKILL）时租约到期后由巡检 {@link #sweepExpired()}
 * 回收重投；重试投递遇到本实例上次被打断的任务时 {@link #releaseIfAbandoned(String)} 就地放回。
 *
 * <p>已知边界：回收发生在「持有者与数据库失联超过一个租约时长」之后，此时如果持有者其实还活着（数据库故障而非进程
 * 死亡），它的任务会被另一个实例接手，出现一次重复执行。彻底消除需要在每次业务写入上带 fencing token （校验写的时候租约仍属于自己），本项目未实现，只在 README 里如实写明。
 */
@Component
class TaskLease {
  private static final Logger log = LoggerFactory.getLogger(TaskLease.class);

  private final TaskRepository tasks;
  private final TaskQueue queue;
  private final WorkbenchMetrics metrics;
  private final String instanceId;
  private final Duration lease;

  /** 本实例此刻真正在跑的任务号：心跳只续它们。 */
  private final Set<String> held = ConcurrentHashMap.newKeySet();

  TaskLease(
      TaskRepository tasks,
      TaskQueue queue,
      WorkbenchMetrics metrics,
      @Value("${workbench.lease.instance-id:}") String configuredInstanceId,
      @Value("${workbench.lease.duration-seconds:90}") long leaseSeconds) {
    this.tasks = tasks;
    this.queue = queue;
    this.metrics = metrics;
    this.lease = Duration.ofSeconds(leaseSeconds);
    this.instanceId =
        configuredInstanceId == null || configuredInstanceId.isBlank()
            ? ephemeralInstanceId()
            : configuredInstanceId.trim();
  }

  /** 本实例标识，写进 {@code tasks.locked_by}，日志与排查时用来分辨是谁在处理。 */
  String instanceId() {
    return instanceId;
  }

  /** 领取任务：成功即代表本实例拿到租约，可以开始执行。 */
  boolean claim(String taskId) {
    return tasks.claimForProcessing(taskId, instanceId, expiry());
  }

  /** 开始处理某个任务，之后心跳会为它续租。 */
  void hold(String taskId) {
    held.add(taskId);
  }

  /** 处理结束（无论成败）就不再为它续租，租约到期后其它实例可以放心回收。 */
  void drop(String taskId) {
    held.remove(taskId);
  }

  /**
   * 重试投递时把「本实例上次被故障打断」或「租约已过期」的任务放回 {@code QUEUED}。
   *
   * @return true 表示已放回可以重新领取；false 表示租约仍在别的实例手上，这次投递应当放弃
   */
  boolean releaseIfAbandoned(String taskId) {
    return tasks.releaseProcessing(taskId, instanceId, Instant.now());
  }

  /**
   * 心跳续租。
   *
   * <p>处理一个长视频可能要几小时，而租约只有几十秒——没有心跳的话，任何一个长任务都会被别的实例当成失联抢走。
   */
  @Scheduled(
      fixedDelayString = "${workbench.lease.heartbeat-interval-millis:20000}",
      initialDelayString = "${workbench.lease.heartbeat-interval-millis:20000}")
  void heartbeat() {
    if (held.isEmpty()) return;
    try {
      tasks.renewLeases(Set.copyOf(held), instanceId, expiry());
    } catch (RuntimeException ex) {
      // 数据库短暂不可达：等下一次心跳。真的一直续不上，说明本实例确实联系不上库，被回收是对的。
      log.warn("任务租约续期失败，等待下一次心跳：instanceId={} error={}", instanceId, ex.getMessage());
    }
  }

  /** 周期巡检：把租约过期仍标着处理中的任务收回并重新投递，否则它们会永远卡在「处理中」。 */
  @Scheduled(
      fixedDelayString = "${workbench.lease.sweep-interval-millis:30000}",
      initialDelayString = "${workbench.lease.sweep-interval-millis:30000}")
  void sweepExpired() {
    reclaimExpired();
  }

  /** 回收一批过期租约并重投。启动恢复与周期巡检走同一条路径。 */
  void reclaimExpired() {
    Instant now = Instant.now();
    List<String> expired;
    try {
      expired = tasks.expiredClaims(now);
    } catch (RuntimeException ex) {
      log.warn("巡检过期任务租约失败，等下一轮：{}", ex.getMessage());
      return;
    }
    for (String taskId : expired) {
      // 自己还在跑的行不能回收：心跳与巡检在同一进程里，正常不会撞上，但数据库抖动时宁可少回收也不能自己抢自己。
      if (held.contains(taskId)) continue;
      try {
        if (!tasks.releaseExpiredClaim(taskId, now)) continue;
      } catch (RuntimeException ex) {
        log.warn("回收过期任务租约失败：taskId={} error={}", taskId, ex.getMessage());
        continue;
      }
      metrics.recordLeaseReclaim();
      log.warn("任务租约已过期，收回并重新投递：taskId={} 上一次持有者已失联", taskId);
      try {
        queue.publish(taskId);
      } catch (TaskQueue.UnavailableException ex) {
        // 队列不可用：任务已回到 QUEUED，broker 恢复后由用户重试或下一次启动恢复重投。
        log.error("回收后重新投递失败，任务保持 QUEUED：taskId={}", taskId, ex);
      }
    }
  }

  /** 优雅停机：立刻交还本实例持有的任务，别的实例可以马上接手，不必等租约自然过期。 */
  void releaseOwned() {
    try {
      int released = tasks.releaseClaimsOwnedBy(instanceId);
      if (released > 0) log.info("停机前交还任务租约：instanceId={} 共 {} 条", instanceId, released);
    } catch (RuntimeException ex) {
      // 交还失败只能让租约自然过期，恢复会晚一个租约时长，但不会丢任务。
      log.warn("停机前交还任务租约失败，交给租约过期回收：{}", ex.getMessage());
    }
  }

  private Instant expiry() {
    return Instant.now().plus(lease);
  }

  /**
   * 未配置实例标识时的默认值：主机名 + 随机后缀。
   *
   * <p>必须带随机后缀——同一台机器上跑两个实例时主机名是一样的，光靠主机名分不清持有者是谁。 生产环境建议显式配置成稳定且唯一的值（如 {@code
   * workbench-a}），日志与数据库里的归属更好读。
   */
  private static String ephemeralInstanceId() {
    String host = System.getenv("HOSTNAME");
    String base = host == null || host.isBlank() ? "local" : host.trim();
    if (base.length() > 48) base = base.substring(0, 48);
    return base + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
