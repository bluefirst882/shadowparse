package ai.toni.videoworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class VideoWorkbenchApplication {
  public static void main(String[] args) { SpringApplication.run(VideoWorkbenchApplication.class, args); }
}

