package ai.toni.videoworkbench;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务列表的键集（keyset）游标，按 {@code created_at desc, id desc} 稳定翻页。
 *
 * <p>对外是不透明字符串，内部编码为 {@code base64url(epochMilli|id)}，客户端无需理解其结构。 时间戳固定按毫秒编码，与 {@code
 * tasks.created_at timestamp(3)} 的存储精度一致：从数据库读出的 {@link Instant}
 * 参与比较时会先落在毫秒精度，游标若携带纳秒会在截断后改变元组比较结果，导致同一 毫秒内的任务被跳过。
 */
record TaskCursor(Instant createdAt, String id) {
  private static final char SEPARATOR = '|';
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /** 解码游标；格式非法（base64 无法解析、缺少分隔符、时间戳非数字）一律抛 400。 */
  static TaskCursor decode(String value) {
    String raw;
    try {
      raw = new String(DECODER.decode(value), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
    int separator = raw.indexOf(SEPARATOR);
    if (separator <= 0 || separator == raw.length() - 1) throw invalid();
    long epochMilli;
    try {
      epochMilli = Long.parseLong(raw.substring(0, separator));
    } catch (NumberFormatException ex) {
      throw invalid();
    }
    return new TaskCursor(Instant.ofEpochMilli(epochMilli), raw.substring(separator + 1));
  }

  String encode() {
    String raw = createdAt.toEpochMilli() + String.valueOf(SEPARATOR) + id;
    return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor 参数无效");
  }
}
