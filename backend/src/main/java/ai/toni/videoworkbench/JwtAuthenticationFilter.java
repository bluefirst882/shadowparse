package ai.toni.videoworkbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class JwtAuthenticationFilter extends OncePerRequestFilter {
  private static final String PREFIX = "Bearer ";

  /** 视频流与导出由浏览器直连打开，无法附加请求头，因此额外允许查询参数携带令牌。 */
  private static final String TOKEN_PARAMETER = "access_token";

  private final JwtService jwt;

  JwtAuthenticationFilter(JwtService jwt) {
    this.jwt = jwt;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    token(request).flatMap(jwt::parse).ifPresent(user -> authenticate(request, user));
    chain.doFilter(request, response);
  }

  private Optional<String> token(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(PREFIX))
      return Optional.of(header.substring(PREFIX.length()).trim());
    return Optional.ofNullable(request.getParameter(TOKEN_PARAMETER));
  }

  private void authenticate(HttpServletRequest request, AuthenticatedUser user) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
