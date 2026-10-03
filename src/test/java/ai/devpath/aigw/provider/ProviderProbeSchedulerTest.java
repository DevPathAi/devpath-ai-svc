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

  private static final class LivenessProbe implements ProviderProbe {
    private final String feature;
    private final String provider;
    private final RuntimeException failure;
    int pings;

    LivenessProbe(String feature, String provider, RuntimeException failure) {
      this.feature = feature;
      this.provider = provider;
      this.failure = failure;
    }

    @Override public String feature() { return feature; }
    @Override public String provider() { return provider; }
    @Override public boolean livenessTarget() { return true; }
    @Override public void ping() {
      pings++;
      if (failure != null) throw failure;
    }
  }

  private static RestClientResponseException unavailable() {
    return new RestClientResponseException(
        "Service Unavailable", 503, "Service Unavailable", new HttpHeaders(), null, null);
  }

  @Test
  void livenessFailureOpensTheLatchImmediately() {
    // 사용자 요청의 「3연속」 게이트를 쓰지 않는다 — 죽은 폴백을 다음 요청 전에 막아야 한다.
    ProviderLatch latch = new ProviderLatch(new MovableClock());
    LivenessProbe ollama = new LivenessProbe("review", "ollama", unavailable());

    new ProviderProbeScheduler(latch, List.of(ollama)).runLivenessProbes();

    assertEquals(1, ollama.pings);
    assertTrue(latch.isOpen("review", "ollama"));
  }

  @Test
  void livenessSuccessLeavesTheUserFailureCountAlone() {
    ProviderLatch latch = new ProviderLatch(new MovableClock());
    latch.recordFailure("review", "ollama", FailureKind.TRANSIENT, null);
    latch.recordFailure("review", "ollama", FailureKind.TRANSIENT, null);

    new ProviderProbeScheduler(latch, List.of(new LivenessProbe("review", "ollama", null)))
        .runLivenessProbes();
    latch.recordFailure("review", "ollama", FailureKind.TRANSIENT, null);

    // 성공한 생존 핑이 recordSuccess 를 하면 카운터가 0 이 되어 세 번째에서 열리지 않는다.
    assertTrue(latch.isOpen("review", "ollama"));
  }

  @Test
  void livenessSkipsALatchThatIsAlreadyOpen() {
    ProviderLatch latch = new ProviderLatch(new MovableClock());
    latch.recordFailure("review", "ollama", FailureKind.AUTH, null);
    LivenessProbe ollama = new LivenessProbe("review", "ollama", null);

    new ProviderProbeScheduler(latch, List.of(ollama)).runLivenessProbes();

    assertEquals(0, ollama.pings);
  }

  @Test
  void livenessIgnoresProbesThatAreNotLivenessTargets() {
    ProviderLatch latch = new ProviderLatch(new MovableClock());
    StubProbe claude = new StubProbe("review", "claude", unavailable());

    new ProviderProbeScheduler(latch, List.of(claude)).runLivenessProbes();

    assertEquals(0, claude.pings);
    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void theOpenedLivenessLatchIsClosedByTheDueProbeOnceOllamaReturns() {
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);
    new ProviderProbeScheduler(latch, List.of(new LivenessProbe("review", "ollama", unavailable())))
        .runLivenessProbes();
    assertTrue(latch.isOpen("review", "ollama"));

    clock.advance(Duration.ofMinutes(2));
    new ProviderProbeScheduler(latch, List.of(new LivenessProbe("review", "ollama", null)))
        .runDueProbes();

    assertFalse(latch.isOpen("review", "ollama"));
  }
}
