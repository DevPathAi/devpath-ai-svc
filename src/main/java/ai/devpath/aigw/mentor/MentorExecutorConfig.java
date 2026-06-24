package ai.devpath.aigw.mentor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 멘토 SSE 스트림 전용 풀(공용 풀 오염 방지, M-1). 동시 세션 백프레셔. */
@Configuration
public class MentorExecutorConfig {

  @Bean(name = "mentorExecutor")
  public AsyncTaskExecutor mentorExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(32);
    executor.setThreadNamePrefix("mentor-sse-");
    executor.initialize();
    return executor;
  }
}
