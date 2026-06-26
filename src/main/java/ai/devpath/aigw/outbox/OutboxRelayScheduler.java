package ai.devpath.aigw.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 주기적 outbox 발행(learning 미러). test 프로파일에선 비활성(IT는 relayOnce 직접 검증). */
@Component
@Profile("!test")
public class OutboxRelayScheduler {

  private final OutboxRelay outboxRelay;

  public OutboxRelayScheduler(OutboxRelay outboxRelay) {
    this.outboxRelay = outboxRelay;
  }

  @Scheduled(fixedDelay = 2000)
  public void relay() {
    outboxRelay.relayOnce();
  }
}
