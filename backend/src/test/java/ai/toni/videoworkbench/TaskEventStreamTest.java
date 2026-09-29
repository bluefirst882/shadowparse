package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 推送通道的两条关键约束：没人订阅时不能为了推送去查库；有订阅者时只能推给任务归属人的连接。
 *
 * <p>用一个记录型的 emitter 代替真实连接，直接数「这个连接收到几次推送」——「推给了谁」这件事靠读代码是看不出来的。 真实的 SSE 编码由框架负责，端到端另有用真后端跑的证据。
 */
class TaskEventStreamTest {
  private static final String OWNER = "user-a";

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final List<RecordingEmitter> created = new ArrayList<>();
  private final TaskEventStream stream =
      new TaskEventStream(
          jdbc,
          timeout -> {
            RecordingEmitter emitter = new RecordingEmitter();
            created.add(emitter);
            return emitter;
          });

  /** 只关心「收到了几次推送」，因此连建连事件一起数；{@code failSends} 用来模拟客户端已经断开。 */
  private static class RecordingEmitter extends SseEmitter {
    private int pushes = 0;
    private boolean failSends = false;

    RecordingEmitter() {
      super(Duration.ofMinutes(1).toMillis());
    }

    // SseEmitter 对 SseEventBuilder 有专门的 send 重载，实际走的是这一个。
    @Override
    public void send(SseEmitter.SseEventBuilder builder) throws IOException {
      record();
    }

    @Override
    public void send(Object object) throws IOException {
      record();
    }

    @Override
    public void send(Object object, MediaType mediaType) throws IOException {
      record();
    }

    private void record() throws IOException {
      pushes++;
      if (failSends) throw new IOException("客户端已断开");
    }
  }

  @Test
  void skipsDatabaseLookupWhenNobodyIsSubscribed() {
    stream.taskChanged("task-1");

    verifyNoInteractions(jdbc);
  }

  @Test
  void pushesToTheOwnerConnectionOnly() {
    RecordingEmitter owner = (RecordingEmitter) stream.subscribe(OWNER);
    RecordingEmitter other = (RecordingEmitter) stream.subscribe("user-b");
    assertEquals(1, owner.pushes, "建连时应先收到一个 ready 事件");
    assertEquals(1, other.pushes);

    when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn(OWNER);
    stream.taskChanged("task-1");

    assertEquals(2, owner.pushes, "归属人的连接应收到这次变化");
    assertEquals(1, other.pushes, "别的账号的连接不应该收到任何东西");
  }

  @Test
  void ignoresChangesToTasksThatNoLongerExist() {
    RecordingEmitter owner = (RecordingEmitter) stream.subscribe(OWNER);
    when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertDoesNotThrow(() -> stream.taskChanged("task-1"));
    assertEquals(1, owner.pushes, "任务已被删除时没有归属人可推，什么都不该发");
  }

  @Test
  void dropsConnectionsWhosePushFailed() {
    RecordingEmitter owner = (RecordingEmitter) stream.subscribe(OWNER);
    when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn(OWNER);
    owner.failSends = true;

    // 推送失败说明客户端已经不在了：这一次算发出去了，但连接必须被摘掉。
    assertDoesNotThrow(() -> stream.taskChanged("task-1"));
    assertEquals(2, owner.pushes);

    stream.taskChanged("task-1");

    assertEquals(2, owner.pushes, "已经断开的连接不应再被推送");
    assertEquals(1, created.size(), "摘掉连接不应该顺带新建连接");
  }
}
