package ai.toni.videoworkbench;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TaskRecovery {
  @Bean
  ApplicationRunner recoverTasks(TaskService service) {
    return arguments -> service.recoverAfterRestart();
  }
}
