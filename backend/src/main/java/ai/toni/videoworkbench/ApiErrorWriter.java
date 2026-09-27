package ai.toni.videoworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 统一错误响应写出组件：把 {@code {code, message, traceId}} 以 UTF-8 JSON 写入响应，供 {@link ApiExceptionHandler} 与
 * {@link SecurityConfig} 共用。
 *
 * <p>traceId 一律取自 MDC（由 {@link TraceIdFilter} 建立），此处不再另行生成。
 */
@Component
class ApiErrorWriter {
  private final ObjectMapper json;

  ApiErrorWriter(ObjectMapper json) {
    this.json = json;
  }

  void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
    write(response, code.status(), code, message);
  }

  /** 显式指定状态码：调用方需要保留枚举未覆盖的状态码时使用，避免被 {@link ErrorCode} 的回落值改写。 */
  void write(HttpServletResponse response, HttpStatus status, ErrorCode code, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    Map<String, String> body = new LinkedHashMap<>();
    body.put("code", code.name());
    body.put("message", message == null || message.isBlank() ? code.defaultMessage() : message);
    body.put("traceId", traceId());
    response.getWriter().write(json.writeValueAsString(body));
  }

  private static String traceId() {
    String value = MDC.get(TraceIdFilter.MDC_KEY);
    return value == null ? "" : value;
  }
}
