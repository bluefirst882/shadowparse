package ai.toni.videoworkbench;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TaskRecovery {
  @Bean
  ApplicationRunner recoverTasks(TaskService service, TaskQueueConsumer consumer) {
    return arguments -> {
      // 顺序不能反：先把残留的 PROCESSING 任务改回 QUEUED 并重投，再开始消费，
      // 否则 broker 重投的旧消息会因为任务还是 PROCESSING 而领不到，被白白确认掉。
      service.recoverAfterRestart();
      consumer.start();
    };
  }
}
