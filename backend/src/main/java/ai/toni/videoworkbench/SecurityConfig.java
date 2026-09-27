package ai.toni.videoworkbench;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
class SecurityConfig {
  private final JwtAuthenticationFilter jwt;
  private final ApiErrorWriter errors;

  SecurityConfig(JwtAuthenticationFilter jwt, ApiErrorWriter errors) {
    this.jwt = jwt;
    this.errors = errors;
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/api/auth/**")
                    .permitAll()
                    // 指标/健康端点供 compose 网络内的 Prometheus 抓取，未鉴权（见 README「可观测性」取舍说明）。
                    .requestMatchers("/actuator/health", "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handlers ->
                handlers
                    .authenticationEntryPoint(
                        (request, response, ex) ->
                            errors.write(response, ErrorCode.UNAUTHORIZED, "未登录或登录已过期"))
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            errors.write(response, ErrorCode.FORBIDDEN, "无权访问该任务")))
        .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
