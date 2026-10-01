package ai.devpath.aigw.provider;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 래치는 프로세스 전역 단일 인스턴스다(replicas: 1 전제 — {@link ProviderLatch} Javadoc 참조). */
@Configuration
public class ProviderLatchConfig {

  @Bean
  public Clock providerClock() {
    return Clock.systemUTC();
  }

  @Bean
  public ProviderLatch providerLatch(Clock providerClock) {
    return new ProviderLatch(providerClock);
  }
}
