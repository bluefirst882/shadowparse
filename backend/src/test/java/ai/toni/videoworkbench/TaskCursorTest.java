package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TaskCursorTest {
  @Test
  void roundTripsMillisecondTimestampAndId() {
    TaskCursor cursor = new TaskCursor(Instant.ofEpochMilli(1_700_000_000_123L), "task-1");

    assertEquals(cursor, TaskCursor.decode(cursor.encode()));
  }

  @Test
  void dropsSubMillisecondPrecisionSoCursorMatchesStoredTimestamp() {
    // tasks.created_at 是 timestamp(3)：读出时纳秒已被截断，游标必须落在毫秒精度才不会漏项。
    Instant withNanos = Instant.ofEpochMilli(1_700_000_000_000L).plusNanos(987_654);

    TaskCursor decoded = TaskCursor.decode(new TaskCursor(withNanos, "task-1").encode());

    assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), decoded.createdAt());
    assertEquals("task-1", decoded.id());
  }

  @Test
  void rejectsCursorThatIsNotBase64() {
    ResponseStatusException error =
        assertThrows(ResponseStatusException.class, () -> TaskCursor.decode("not base64!!"));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    assertEquals("cursor 参数无效", error.getReason());
  }

  @Test
  void rejectsEmptyCursor() {
    assertThrows(ResponseStatusException.class, () -> TaskCursor.decode(""));
  }

  @Test
  void rejectsCursorWithoutIdField() {
    assertThrows(ResponseStatusException.class, () -> TaskCursor.decode(encode("1700000000000")));
  }

  @Test
  void rejectsCursorWithUnparsableTimestamp() {
    assertThrows(
        ResponseStatusException.class, () -> TaskCursor.decode(encode("not-a-number|task-1")));
  }

  private String encode(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
