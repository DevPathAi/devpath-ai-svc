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
    clock.advance(Duration.ofMinutes(59));
    assertTrue(latch.isOpen("review", "claude"));   // 상한보다 **작지도** 않다
    clock.advance(Duration.ofMinutes(2));
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

  @Test
  void transientBackoffStartsAtOneMinuteDoublesAndCapsAtThirtyMinutes() {
    // 지금까지 transient 쪽은 「3연속에 열린다」만 검증했고 기한의 크기·증가·상한은 통째로
    // 비어 있었다(rate_limit 만 있었다).
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    for (int i = 0; i < 3; i++) {
      latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    }
    clock.advance(Duration.ofSeconds(59));
    assertTrue(latch.isOpen("review", "claude"));   // 1분
    clock.advance(Duration.ofSeconds(2));
    assertFalse(latch.isOpen("review", "claude"));

    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    clock.advance(Duration.ofSeconds(119));
    assertTrue(latch.isOpen("review", "claude"));   // 두 배인 2분
    clock.advance(Duration.ofSeconds(2));
    assertFalse(latch.isOpen("review", "claude"));

    for (int i = 0; i < 10; i++) {                  // 4·8·16·30(상한)…
      latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
      clock.advance(Duration.ofHours(1));
    }
    latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    clock.advance(Duration.ofMinutes(29));
    assertTrue(latch.isOpen("review", "claude"));   // 상한 30분 — 양쪽으로 못박는다
    clock.advance(Duration.ofMinutes(2));
    assertFalse(latch.isOpen("review", "claude"));
  }

  @Test
  void rateLimitAndTransientBackoffsDoNotContaminateEachOther() {
    // 스펙 §3 은 종류마다 base·cap 을 따로 준다(5분/1시간 vs 1분/30분). 사다리를 하나로 공유하면
    // 양방향으로 틀린다 — 429(5분) 뒤의 transient 가 1분이 아니라 10분이 되고,
    // transient(1분) 뒤의 헤더 없는 429 가 5분이 아니라 2분이 된다.
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);  // 5분
    clock.advance(Duration.ofMinutes(6));
    for (int i = 0; i < 3; i++) {
      latch.recordFailure("review", "claude", FailureKind.TRANSIENT, null);
    }
    clock.advance(Duration.ofSeconds(61));
    assertFalse(latch.isOpen("review", "claude"));  // transient 는 1분에서 시작한다

    MovableClock clock2 = new MovableClock();
    ProviderLatch latch2 = new ProviderLatch(clock2);

    for (int i = 0; i < 3; i++) {
      latch2.recordFailure("community-seed", "claude", FailureKind.TRANSIENT, null);  // 1분
    }
    clock2.advance(Duration.ofMinutes(2));
    latch2.recordFailure("community-seed", "claude", FailureKind.RATE_LIMIT, null);
    clock2.advance(Duration.ofMinutes(4));
    assertTrue(latch2.isOpen("community-seed", "claude"));   // 2분이었다면 벌써 닫혔다
    clock2.advance(Duration.ofMinutes(2));
    assertFalse(latch2.isOpen("community-seed", "claude"));  // 429 는 5분에서 시작한다
  }

  @Test
  void aProbeFailureReopensTheLatchWithoutTheThreeConsecutiveGate() {
    // 「3연속」 규칙은 사용자에게 보이는 한 번의 딸꾹질로 provider 를 끊지 않으려는 것이다.
    // 탐색은 사용자 트래픽이 아니다 — 기한이 지나 한 번 탐색했는데 실패했다면 그것으로 충분하다.
    // 다시 닫지 않으면 다음 틱(기본 1분)까지 사용자 요청이 죽은 provider 의 타임아웃을 문다.
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);
    clock.advance(Duration.ofMinutes(6));
    assertFalse(latch.isOpen("review", "claude"));
    assertEquals(1, latch.dueProbes().size());

    assertTrue(latch.recordProbeFailure("review", "claude", FailureKind.TRANSIENT, null));

    assertTrue(latch.isOpen("review", "claude"));
    assertEquals(List.of(), latch.dueProbes());
  }

  @Test
  void aContentFailureProbeStopsProbingInsteadOfStayingPastDueForever() {
    // 404(모델명 오타)는 OUTPUT_INVALID 로 분류돼 recordFailure 가 아무 일도 하지 않는다.
    // 그러면 기한이 지난 항목이 영원히 남아 스케줄러가 1분마다 핑하면서 "stays open" 이라는
    // 거짓 로그를 남긴다. 가용성 문제가 아니므로 막지는 않되, 탐색은 포기해야 한다.
    MovableClock clock = new MovableClock();
    ProviderLatch latch = new ProviderLatch(clock);

    latch.recordFailure("review", "claude", FailureKind.AUTH, null);
    clock.advance(Duration.ofMinutes(31));
    assertEquals(1, latch.dueProbes().size());

    assertFalse(latch.recordProbeFailure("review", "claude", FailureKind.OUTPUT_INVALID, null));

    assertFalse(latch.isOpen("review", "claude"));
    assertEquals(List.of(), latch.dueProbes());
  }

  @Test
  void theLatchStateFieldsReadOutsideTheMonitorAreVolatile() throws Exception {
    // isOpen()·dueProbes() 는 State 의 필드를 **synchronized 밖에서** 읽는다. states 가
    // ConcurrentHashMap 이라 State **참조**는 안전하게 공개되지만 그 필드는 아니다 — 쓰기
    // 스레드의 monitor exit 과 읽기 사이에 happens-before 가 없어 요청 스레드가 낡은
    // openUntil 을 무기한 볼 수 있다(차단한 provider 를 계속 때리거나, 닫힌 provider 를 계속 건너뛴다).
    // JMM 은 낡은 값을 **허용**할 뿐 강제하지 않아 가시성 결함은 결정적으로 재현할 수 없다 —
    // 그래서 소스 텍스트가 아니라 **컴파일된 필드 수식어**로 못박는다.
    Class<?> state = Class.forName("ai.devpath.aigw.provider.ProviderLatch$State");
    for (String name : List.of("openUntil", "rateLimitBackoff", "transientBackoff", "transientRun")) {
      assertTrue(
          java.lang.reflect.Modifier.isVolatile(state.getDeclaredField(name).getModifiers()),
          name + " 은 모니터 밖에서 읽힐 수 있다 — volatile 이어야 한다");
    }
  }
}
