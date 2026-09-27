package ai.toni.videoworkbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求建立 traceId：优先沿用合法的 {@code X-Trace-Id} 请求头，否则新生成 32 位十六进制值。
 *
 * <p>以最高优先级注册，确保在 Spring Security 过滤器链之前生效，使鉴权失败等早期错误也能带上追踪号。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Trace-Id";
  static final String MDC_KEY = "traceId";

  private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = traceId(request);
    MDC.put(MDC_KEY, traceId);
    response.setHeader(HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private String traceId(HttpServletRequest request) {
    String provided = request.getHeader(HEADER);
    if (provided != null && ACCEPTED.matcher(provided).matches()) return provided;
    return UUID.randomUUID().toString().replace("-", "");
  }
}
