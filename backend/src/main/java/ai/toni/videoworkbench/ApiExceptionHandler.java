package ai.toni.videoworkbench;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一错误响应 Advice：把所有异常收敛为 {@code {code, message, traceId}} 结构，状态码保持不变。
 *
 * <p>未捕获异常会用日志记录完整堆栈（日志格式已带 traceId），但只向客户端返回固定文案，绝不泄漏异常细节。
 */
@RestControllerAdvice
class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final ApiErrorWriter writer;

  ApiExceptionHandler(ApiErrorWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ResponseStatusException.class)
  void handleResponseStatus(ResponseStatusException ex, HttpServletResponse response)
      throws IOException {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) {
      log.error("无法识别的响应状态码：{}", ex.getStatusCode(), ex);
      writer.write(response, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
      return;
    }
    // 状态码以异常为准，ErrorCode 只提供粗分类，避免被回落值改写。
    writer.write(response, status, ErrorCode.of(status), ex.getReason());
  }

  @ExceptionHandler(AccessDeniedException.class)
  void handleAccessDenied(AccessDeniedException ex, HttpServletResponse response)
      throws IOException {
    writer.write(response, ErrorCode.FORBIDDEN, ex.getMessage());
  }

  @ExceptionHandler(AuthenticationException.class)
  void handleAuthentication(AuthenticationException ex, HttpServletResponse response)
      throws IOException {
    writer.write(response, ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  void handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletResponse response) throws IOException {
    writer.write(
        response, ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    MethodArgumentNotValidException.class,
    BindException.class,
    MissingServletRequestPartException.class,
    // 客户端把非 multipart 请求打到上传接口时抛出；它是客户端错误，不能落到兜底变成 500。
    // 子类 MaxUploadSizeExceededException 有更精确的处理器，仍按 413 返回。
    MultipartException.class
  })
  void handleBadRequest(Exception ex, HttpServletResponse response) throws IOException {
    writer.write(response, ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  void handlePayloadTooLarge(MaxUploadSizeExceededException ex, HttpServletResponse response)
      throws IOException {
    writer.write(
        response, ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.defaultMessage());
  }

  @ExceptionHandler(Exception.class)
  void handleUnexpected(Exception ex, HttpServletResponse response) throws IOException {
    // 路径不存在、媒体类型不支持等 Spring 自带错误实现了 ErrorResponse，属于客户端错误，
    // 必须按原始状态码返回，不能因为兜底处理器统一变成 500。
    if (ex instanceof ErrorResponse failure && !failure.getStatusCode().is5xxServerError()) {
      HttpStatus status = HttpStatus.resolve(failure.getStatusCode().value());
      if (status != null) {
        writer.write(response, status, ErrorCode.of(status), null);
        return;
      }
    }
    log.error("未处理的服务端异常", ex);
    writer.write(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, null);
  }
}
