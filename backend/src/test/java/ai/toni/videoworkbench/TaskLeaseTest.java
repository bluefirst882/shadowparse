package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 多实例认领的租约行为：领取写持有者、心跳只续在跑的任务、巡检只回收过期租约。 */
class TaskLeaseTest {
  private static final String INSTANCE = "instance-a";

  private final TaskRepository tasks = Mockito.mock(TaskRepository.class);
  private final TaskQueue queue = Mockito.mock(TaskQueue.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final TaskLease lease =
      new TaskLease(tasks, queue, new WorkbenchMetrics(registry), INSTANCE, 90);

  /** 领取要把持有者与租约写进同一条件更新里，其它实例据此知道这条任务有人管。 */
  @Test
  void claimsWithOwnerAndLease() {
    when(tasks.claimForProcessing(eq("task-1"), eq(INSTANCE), any())).thenReturn(true);

    assertTrue(lease.claim("task-1"));

    verify(tasks).claimForProcessing(eq("task-1"), eq(INSTANCE), any());
    assertTrue(lease.instanceId().equals(INSTANCE));
  }

  /** 空闲实例不该每 20 秒往数据库打一次空更新。 */
  @Test
  void doesNotRenewWhenNothingIsHeld() {
    lease.heartbeat();

    verify(tasks, never()).renewLeases(anyList(), any(), any());
  }

  /** 心跳只续本实例真正在处理的任务：被放弃却仍标着 PROCESSING 的行不在其中，租约才能自然过期。 */
  @Test
  void renewsOnlyHeldTasks() {
    lease.hold("task-1");
    lease.hold("task-2");
    lease.drop("task-2");

    lease.heartbeat();

    verify(tasks).renewLeases(eq(java.util.Set.of("task-1")), eq(INSTANCE), any());
  }

  /** 巡检把过期租约收回并重投，任务不会永远卡在「处理中」。 */
  @Test
  void reclaimsExpiredLeaseAndRepublishes() {
    when(tasks.expiredClaims(any())).thenReturn(List.of("task-1"));
    when(tasks.releaseExpiredClaim(eq("task-1"), any())).thenReturn(true);

    lease.sweepExpired();

    verify(tasks).releaseExpiredClaim(eq("task-1"), any());
    verify(queue).publish("task-1");
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, registry.counter(WorkbenchMetrics.LEASE_RECLAIMS).count());
  }

  /** 两个实例同时巡检时只有抢到条件更新的那个能重投，避免同一任务被重复投递两次。 */
  @Test
  void doesNotRepublishWhenAnotherInstanceWonTheReclaim() {
    when(tasks.expiredClaims(any())).thenReturn(List.of("task-1"));
    when(tasks.releaseExpiredClaim(eq("task-1"), any())).thenReturn(false);

    lease.sweepExpired();

    verify(queue, never()).publish(any());
  }

  /** 本实例正在跑的任务即使在巡检里出现也不回收：数据库抖动时宁可少回收，也不能自己抢自己。 */
  @Test
  void neverReclaimsTaskItIsStillRunning() {
    when(tasks.expiredClaims(any())).thenReturn(List.of("task-1"));
    lease.hold("task-1");

    lease.sweepExpired();

    verify(tasks, never()).releaseExpiredClaim(eq("task-1"), any());
    verify(queue, never()).publish(any());
  }

  /** 重试投递只有在本实例已放弃（或租约过期）时才收回，别人的活返回 false。 */
  @Test
  void releasesOnlyAbandonedTaskOnRetryDelivery() {
    when(tasks.releaseProcessing(eq("task-1"), eq(INSTANCE), any())).thenReturn(false);

    assertFalse(lease.releaseIfAbandoned("task-1"));

    verify(tasks).releaseProcessing(eq("task-1"), eq(INSTANCE), any());
  }

  /** 优雅停机立刻交还，不必等租约自然过期。 */
  @Test
  void releasesOwnedClaimsOnShutdown() {
    lease.releaseOwned();

    verify(tasks).releaseClaimsOwnedBy(INSTANCE);
  }

  /** 未配置实例标识时也要能区分同机多实例：主机名之外必须带随机后缀。 */
  @Test
  void generatesDistinctInstanceIdsWhenNotConfigured() {
    TaskLease first = new TaskLease(tasks, queue, new WorkbenchMetrics(registry), "  ", 90);
    TaskLease second = new TaskLease(tasks, queue, new WorkbenchMetrics(registry), null, 90);

    assertFalse(first.instanceId().isBlank());
    assertFalse(first.instanceId().equals(second.instanceId()));
  }
}
