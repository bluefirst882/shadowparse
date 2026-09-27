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
        .allowedMethods("GET", "POST", "DELETE", "OPTIONS");
  }
}
