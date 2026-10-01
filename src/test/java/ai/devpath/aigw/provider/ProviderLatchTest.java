package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderLatchTest {

  /** 테스트가 시간을 직접 밀어 준다 — 실시간 sleep 금지. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-10-01T00:00:00Z");

    void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  @Test
  void startsClosed() {
    assertFalse(new ProviderLatch(new MovableClock()).isOpen("review", "claude"));
  }

  @Test
  void authOpensImmediatelyForThirtyMinutes() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.AUTH, null);

    assertTrue(latch.isOpen("review", "claude"));
    clock.advance(Duration.ofMinutes(29));
    assertTrue(latch.isOpen("review", "claude"));
    clock.advance(Duration.ofMinutes(2));
    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void rateLimitTrustsTheRetryAfterHeader() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, Duration.ofSeconds(90));

    clock.advance(Duration.ofSeconds(89));
    assertTrue(latch.isOpen("review", "claude"));
    clock.advance(Duration.ofSeconds(2));
    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void rateLimitWithoutAHeaderStartsAtFiveMinutesAndDoublesToAnHourCap() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    assertFalse(latch.isOpen("review", "claude"));

    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);
    clock.advance(Duration.ofMinutes(9));
    assertTrue(latch.isOpen("review", "claude"));   // 10분으로 늘었다
    clock.advance(Duration.ofMinutes(2));
    assertFalse(latch.isOpen("review", "claude"));

    for (int i = 0; i < 10; i++) {
      latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);
      clock.advance(Duration.ofHours(2));
    }
    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);
    clock.advance(Duration.ofMinutes(61));
    assertFalse(latch.isOpen("review", "claude"));  // 상한 1시간
  }

  @Test
  void transientOpensOnlyOnTheThirdConsecutiveFailure() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    assertFalse(latch.isOpen("review", "claude"));
    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    assertFalse(latch.isOpen("review", "claude"));
    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    assertTrue(latch.isOpen("review", "claude"));
  }

  @Test
  void oneSuccessResetsTheTransientRun() {
    // Review Focus 5: 2 fail + success + 2 fail must not open.
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    latch.recordSuccess("review", "claude");
    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);

    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void badRequestAndOutputInvalidNeverOpen() {
    ProviderLatch latch = new ProviderLatch(new MovableClock());

    for (int i = 0; i < 10; i++) {
      latch.recordFailure("review", "claude", FailureKind.BAD_REQUEST, null);
      latch.recordFailure("review", "claude", FailureKind.OUTPUT_INVALID, null);
    }

    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void successClosesAnOpenLatch() {
    ProviderLatch latch = new ProviderLatch(new MovableClock());

    latch.recordFailure("review", "claude", FailureKind.AUTH, null);
    latch.recordSuccess("review", "claude");

    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void keepsFeaturesAndProvidersIndependent() {
    ProviderLatch latch = new ProviderLatch(new MovableClock());

    latch.recordFailure("review", "claude", FailureKind.AUTH, null);

    assertTrue(latch.isOpen("review", "claude"));
    assertFalse(latch.isOpen("community-seed", "claude"));
    assertFalse(latch.isOpen("review", "ollama"));
  }

  @Test
  void dueProbesReportsOnlyEntriesWhoseDeadlinePassed() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.AUTH, null);              // 30분
    latch.recordFailure("community-seed", "claude", FailureKind.RATE_LIMIT,
        Duration.ofMinutes(1));                                                   // 1분

    // dueProbes() 의 순서는 의미를 갖지 않는다(스케줄러가 각각 ping 한다) — 내용으로 단언한다.
    assertEquals(Set.of(), Set.copyOf(latch.dueProbes()));

    clock.advance(Duration.ofMinutes(2));
    assertEquals(Set.of(new ProviderLatch.Probe("community-seed", "claude")),
        Set.copyOf(latch.dueProbes()));

    clock.advance(Duration.ofMinutes(29));
    assertEquals(
        Set.of(new ProviderLatch.Probe("review", "claude"),
               new ProviderLatch.Probe("community-seed", "claude")),
        Set.copyOf(latch.dueProbes()));
    assertEquals(2, latch.dueProbes().size());   // 중복 없이 정확히 둘
  }
}
