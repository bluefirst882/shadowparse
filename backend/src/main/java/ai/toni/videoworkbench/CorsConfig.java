package ai.toni.videoworkbench;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class CorsConfig implements WebMvcConfigurer {
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        // 5173 is the Vite development server; 5174 is the Docker/Nginx frontend.
        .allowedOrigins(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174")
        // PUT 是分片上传用的方法：浏览器对同源的写请求也会带 Origin 头，方法不在白名单里同样会被判成
        // 「Invalid CORS request」而返回 403，所以在白名单里必须列全实际用到的动词。
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
  }
}
