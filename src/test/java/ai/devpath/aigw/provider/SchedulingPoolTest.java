package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 탐색 루프와 outbox relay 가 스케줄러 스레드 하나를 나눠 쓰지 않게 한다(최종 리뷰 I-3).
 *
 * <p>Boot 기본 스케줄러는 스레드 1개다(4.0.7 {@code spring.task.scheduling.pool.size} 기본 1). 그러면 응답 없는
 * Ollama 를 생존 탐색하는 동안(기능 셋 × (연결 3초 + 읽기 5초) = 최대 24초) 2초 주기 outbox 발행과 복구 탐색이
 * 함께 멈춘다. 소스 텍스트가 아니라 <b>실제 application.yml 을 Boot 자동 구성에 넣어 만든 스케줄러</b>로 단언한다.
 */
class SchedulingPoolTest {

  @Configuration
  @EnableScheduling
  static class Scheduling {}

  @Test
  void givesEachScheduledLoopItsOwnThread() throws Exception {
    List<PropertySource<?>> yml = new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"));

    new ApplicationContextRunner()
        .withInitializer(context -> yml.forEach(context.getEnvironment().getPropertySources()::addLast))
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(Scheduling.class)
        .run(context -> {
          ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
          // OutboxRelayScheduler.relay · ProviderProbeScheduler.runDueProbes · runLivenessProbes
          assertEquals(3, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        });
  }
}
