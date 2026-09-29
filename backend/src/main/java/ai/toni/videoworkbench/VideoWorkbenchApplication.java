package ai.toni.videoworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// 认证完全由 JwtAuthenticationFilter 与 users 表完成，不需要 Spring Security 的内置内存用户。
@EnableAsync
// 任务租约的心跳与过期巡检依赖定时任务（见 TaskLease）。
@EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class VideoWorkbenchApplication {
  public static void main(String[] args) {
    SpringApplication.run(VideoWorkbenchApplication.class, args);
  }
}
