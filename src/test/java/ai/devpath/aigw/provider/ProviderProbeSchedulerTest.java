package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class ProviderProbeSchedulerTest {

  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-10-01T00:00:00Z");

    void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  private static final class StubProbe implements ProviderProbe {
    private final String feature;
    private final String provider;
    private final RuntimeException failure;
    int pings;

    StubProbe(String feature, String provider, RuntimeException failure) {
      this.feature = feature;
      this.provider = provider;
      this.failure = failure;
    }

    @Override public String feature() { return feature; }
    @Override public String provider() { return provider; }
    @Override public void ping() {
      pings++;
      if (failure != null) throw failure;
    }
  }

  @Test
  void doesNotPingWhileTheDeadlineHasNotPassed() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);
    latch.recordFailure("review", "claude", FailureKind.AUTH, null);
    StubProbe probe = new StubProbe("review", "claude", null);

    new ProviderProbeScheduler(latch, List.of(probe)).runDueProbes();

    assertEquals(0, probe.pings);
    assertTrue(latch.isOpen("review", "claude"));
  }

  @Test
  void closesTheLatchWhenTheProbeSucceeds() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);
    latch.recordFailure("review", "claude", FailureKind.AUTH, null);
    StubProbe probe = new StubProbe("review", "claude", null);
    clock.advance(Duration.ofMinutes(31));

    new ProviderProbeScheduler(latch, List.of(probe)).runDueProbes();

    assertEquals(1, probe.pings);
    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void extendsTheDeadlineWhenTheProbeStillFails() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);
    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null); // 5분
    clock.advance(Duration.ofMinutes(6));
    StubProbe probe = new StubProbe("review", "claude",
        new RestClientResponseException("429", 429, "Too Many Requests",
            new HttpHeaders(), null, null));

    new ProviderProbeScheduler(latch, List.of(probe)).runDueProbes();

    assertEquals(1, probe.pings);
    assertTrue(latch.isOpen("review", "claude"));   // 10분으로 늘었다
    clock.advance(Duration.ofMinutes(11));
    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void ignoresADueEntryThatHasNoRegisteredProbe() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);
    latch.recordFailure("community-seed", "claude", FailureKind.AUTH, null);
    clock.advance(Duration.ofMinutes(31));

    // review 용 probe 만 등록돼 있다 — 예외 없이 그냥 건너뛴다.
    new ProviderProbeScheduler(latch, List.of(new StubProbe("review", "claude", null)))
        .runDueProbes();

    assertFalse(latch.isOpen("community-seed", "claude")); // 기한이 지나 이미 닫힌 상태
  }

  @Test
  void oneFailingProbeDoesNotStopTheOthers() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);
    latch.recordFailure("review", "claude", FailureKind.AUTH, null);
    latch.recordFailure("retention", "claude", FailureKind.AUTH, null);
    clock.advance(Duration.ofMinutes(31));

    StubProbe failing = new StubProbe("review", "claude", new IllegalStateException("boom"));
    StubProbe healthy = new StubProbe("retention", "claude", null);

    new ProviderProbeScheduler(latch, List.of(failing, healthy)).runDueProbes();

    assertEquals(1, failing.pings);
    assertEquals(1, healthy.pings);
    assertFalse(latch.isOpen("retention", "claude"));
    // 실패한 쪽의 래치도 단언한다. IllegalStateException 은 OUTPUT_INVALID 로 분류되므로
    // 막지 않는 것이 맞고(내용 실패), **다시 탐색 대상으로 남지도 않아야** 한다 —
    // 남으면 스케줄러가 1분마다 영원히 핑한다.
    assertFalse(latch.isOpen("review", "claude"));
    assertEquals(List.of(), latch.dueProbes());
  }

  @Test
  void toleratesAnEmptyProbeList() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);
    latch.recordFailure("review", "claude", FailureKind.AUTH, null);
    clock.advance(Duration.ofMinutes(31));

    new ProviderProbeScheduler(latch, List.of()).runDueProbes();   // 예외 없이 지나간다
  }
}
