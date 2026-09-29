package ai.toni.videoworkbench;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务变化的推送通道（Server-Sent Events）：界面不再靠定时轮询，而是由服务端在任务状态真的变了的时候推。
 *
 * <p>{@link TaskRepository} 每次写完任务状态都会调用 {@link #taskChanged(String)}，这里查出该任务的归属人，
 * 只把「有变化」的通知发给这个账号的连接。 推送的是信号而不是整行数据：客户端收到后按自己已鉴权的接口重新取数，推送通道里就不必再造一套归属校验与序列化，
 * 也因此不会把别的账号的任务内容带到某个连接上（通知本身只带任务号，且只发给归属人）。
 *
 * <p>没有任何订阅者时连归属人都不会去查，因此不改变原有处理路径的开销。
 */
@Component
class TaskEventStream {
  private static final Logger log = LoggerFactory.getLogger(TaskEventStream.class);

  /** 连接保持时长：到点关闭后由浏览器自动重连，避免中间代理长期占着一个不用的连接。 */
  private static final long TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

  private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
  private final JdbcTemplate jdbc;
  private final LongFunction<SseEmitter> emitters;

  // 有两个构造器时 Spring 不会自己挑，必须显式指定生产用的这个（另一个只给测试注入用）。
  @Autowired
  TaskEventStream(JdbcTemplate jdbc) {
    this(jdbc, SseEmitter::new);
  }

  /** 允许测试注入可观察的连接实现，用来断言「到底推给了谁、推了几次」。 */
  TaskEventStream(JdbcTemplate jdbc, LongFunction<SseEmitter> emitters) {
    this.jdbc = jdbc;
    this.emitters = emitters;
  }

  /** 订阅一个账号的任务变化；同一账号可以有多个连接（多开页面、多台设备）。 */
  SseEmitter subscribe(String ownerId) {
    SseEmitter emitter = emitters.apply(TIMEOUT_MILLIS);
    subscribers.computeIfAbsent(ownerId, key -> ConcurrentHashMap.newKeySet()).add(emitter);
    Runnable remove = () -> unsubscribe(ownerId, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(error -> remove.run());
    try {
      // 首个事件让客户端确认通道已建立（EventSource 收到任何事件才算连上）。
      emitter.send(SseEmitter.event().name("ready").data("ok"));
    } catch (IOException | IllegalStateException ex) {
      log.debug("订阅者已断开，忽略初始事件：{}", ex.getMessage());
      remove.run();
    }
    return emitter;
  }

  private void unsubscribe(String ownerId, SseEmitter emitter) {
    Set<SseEmitter> set = subscribers.get(ownerId);
    if (set == null) return;
    set.remove(emitter);
    if (set.isEmpty()) subscribers.remove(ownerId, set);
  }

  /** 任务状态有变化：只有归属人正在订阅时才推送。 */
  void taskChanged(String taskId) {
    if (subscribers.isEmpty()) return;
    taskChanged(taskId, ownerOf(taskId));
  }

  /** 任务已经删除、查不到归属人时用这个重载：归属人在调用点本来就知道。 */
  void taskChanged(String taskId, String ownerId) {
    if (ownerId == null || subscribers.isEmpty()) return;
    Set<SseEmitter> set = subscribers.get(ownerId);
    if (set == null || set.isEmpty()) return;
    for (SseEmitter emitter : set) {
      try {
        // 同一个连接可能被多个线程同时写（消费者线程 + 请求线程），而 send 不是线程安全的。
        synchronized (emitter) {
          emitter.send(SseEmitter.event().name("tasks").data(taskId));
        }
      } catch (IOException | IllegalStateException ex) {
        // 客户端已经断开：只影响这个订阅者，摘掉它即可。
        unsubscribe(ownerId, emitter);
      }
    }
  }

  private String ownerOf(String taskId) {
    try {
      return jdbc.queryForObject("select owner_id from tasks where id=?", String.class, taskId);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }
}
