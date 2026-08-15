package ai.devpath.aigw.mentor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 멘토 SSE 스트림 전용 풀(공용 풀 오염 방지, M-1). 동시 세션 백프레셔. */
@Configuration
public class MentorExecutorConfig {

  private static final int TERMINAL_IO_THREADS = 16;
  private static final int HEARTBEAT_IO_THREADS = 8;

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

  @Bean(name = "mentorTerminalIoExecutor")
  public AsyncTaskExecutor mentorTerminalIoExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(TERMINAL_IO_THREADS);
    executor.setMaxPoolSize(TERMINAL_IO_THREADS);
    // One queue slot per admitted session leaves bounded handoff room while completed workers
    // release admission immediately before ThreadPoolExecutor marks their thread idle.
    executor.setQueueCapacity(MentorTerminalIoDispatcher.MAX_SESSIONS);
    executor.setThreadNamePrefix("mentor-terminal-io-");
    executor.initialize();
    return executor;
  }

  @Bean(name = "mentorHeartbeatIoExecutor")
  public AsyncTaskExecutor mentorHeartbeatIoExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(HEARTBEAT_IO_THREADS);
    executor.setMaxPoolSize(HEARTBEAT_IO_THREADS);
    executor.setQueueCapacity(MentorTerminalIoDispatcher.MAX_SESSIONS - HEARTBEAT_IO_THREADS);
    executor.setThreadNamePrefix("mentor-heartbeat-io-");
    executor.initialize();
    return executor;
  }

  @Bean(name = "mentorDeadlineScheduler", destroyMethod = "shutdownNow")
  public ScheduledExecutorService mentorDeadlineScheduler() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
      Thread thread = new Thread(runnable, "mentor-deadline-");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
