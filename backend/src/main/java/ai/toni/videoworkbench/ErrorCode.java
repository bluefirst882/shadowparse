package ai.toni.videoworkbench;

import org.springframework.http.HttpStatus;

/**
 * 统一错误响应中的粗分类错误码。
 *
 * <p>code 只做粗分类，精确语义由 HTTP 状态码承载：因此状态码匹配不到时统一回落到 5xx → {@link #INTERNAL_ERROR}、其它 → {@link
 * #INVALID_REQUEST}，不额外新增枚举值。
 */
enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数有误"),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该任务"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),
  CONFLICT(HttpStatus.CONFLICT, "请求与当前状态冲突"),
  PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "上传内容超过大小限制"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  HttpStatus status() {
    return status;
  }

  String defaultMessage() {
    return defaultMessage;
  }

  static ErrorCode of(HttpStatus status) {
    for (ErrorCode code : values()) {
      if (code.status == status) return code;
    }
    return status.is5xxServerError() ? INTERNAL_ERROR : INVALID_REQUEST;
  }
}
