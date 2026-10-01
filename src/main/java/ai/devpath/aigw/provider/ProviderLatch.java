package ai.devpath.aigw.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code (feature, provider)} 단위 차단 상태. <b>열림(open)</b> 이면 체인이 그 provider 를 건너뛴다.
 *
 * <p><b>전제: replicas = 1.</b> gitops {@code apps/devpath-ai-svc/base/deployment.yaml} 이
 * {@code replicas: 1} 이라 공유 저장소가 필요 없다. <b>스케일아웃하면 파드마다 래치가 갈라져
 * 일관성이 깨진다</b> — 그때는 공유 저장소(Redis 등)로 옮겨야 한다.
 */
public class ProviderLatch {

  /** 배경 복구 탐색이 소비하는 항목. */
  public record Probe(String feature, String provider) {}

  private static final Duration AUTH_DEADLINE = Duration.ofMinutes(30);
  private static final Duration RATE_LIMIT_BASE = Duration.ofMinutes(5);
  private static final Duration RATE_LIMIT_CAP = Duration.ofHours(1);
  private static final Duration TRANSIENT_BASE = Duration.ofMinutes(1);
  private static final Duration TRANSIENT_CAP = Duration.ofMinutes(30);
  private static final int TRANSIENT_THRESHOLD = 3;

  /**
   * 쓰기는 전부 {@code synchronized (s)} 안에서 하지만 {@link #isOpen}·{@link #dueProbes} 는
   * <b>모니터 밖에서</b> 읽는다. {@code states} 가 {@code ConcurrentHashMap} 이라 State
   * <b>참조</b>는 안전하게 공개되지만 그 필드는 아니다 — happens-before 가 없으면 요청 스레드가
   * 낡은 {@code openUntil} 을 무기한 볼 수 있다(차단한 provider 를 계속 때리거나, 닫힌 provider 를
   * 계속 건너뛴다). 그래서 전부 {@code volatile} 이다. 비용은 무시할 수준이고, 앞으로 어떤 필드가
   * 모니터 밖에서 읽히더라도 안전하다.
   *
   * <p>백오프 사다리는 <b>종류마다 따로</b> 둔다. 하나로 공유하면 스펙 §3 이 종류별로 준 base·cap
   * (5분/1시간 vs 1분/30분)이 서로를 오염시킨다 — 429 뒤의 transient 가 1분이 아니라 10분이 되고,
   * transient 뒤의 헤더 없는 429 가 5분이 아니라 2분이 된다.
   */
  private static final class State {
    volatile Instant openUntil;          // null = 닫힘
    volatile Duration rateLimitBackoff;  // 429 전용 사다리
    volatile Duration transientBackoff;  // 5xx·IO 전용 사다리
    volatile int transientRun;
  }

  private final Clock clock;
  private final Map<String, State> states = new ConcurrentHashMap<>();

  public ProviderLatch(Clock clock) {
    this.clock = clock;
  }

  private static String key(String feature, String provider) {
    return feature + "/" + provider;
  }

  public boolean isOpen(String feature, String provider) {
    State s = states.get(key(feature, provider));
    return s != null && s.openUntil != null && clock.instant().isBefore(s.openUntil);
  }

  public void recordSuccess(String feature, String provider) {
    State s = states.computeIfAbsent(key(feature, provider), k -> new State());
    synchronized (s) {
      s.openUntil = null;
      s.rateLimitBackoff = null;
      s.transientBackoff = null;
      s.transientRun = 0;
    }
  }

  public void recordFailure(
      String feature, String provider, FailureKind kind, Duration retryAfter) {
    State s = states.computeIfAbsent(key(feature, provider), k -> new State());
    synchronized (s) {
      switch (kind) {
        case BAD_REQUEST, OUTPUT_INVALID -> {
          // 내용 실패는 래치를 건드리지 않는다.
        }
        case AUTH -> {
          s.transientRun = 0;
          open(s, AUTH_DEADLINE);
        }
        case RATE_LIMIT -> {
          s.transientRun = 0;
          open(s, retryAfter != null ? retryAfter : nextRateLimit(s));
        }
        case TRANSIENT -> {
          s.transientRun++;
          if (s.transientRun >= TRANSIENT_THRESHOLD) {
            open(s, nextTransient(s));
          }
        }
      }
    }
  }

  /**
   * 배경 탐색({@link ProviderProbeScheduler})의 실패. 사용자 요청 실패와 <b>다르게</b> 다룬다.
   *
   * <ul>
   *   <li><b>TRANSIENT 의 「3연속」 게이트를 적용하지 않는다.</b> 그 규칙은 사용자에게 보이는 한 번의
   *       딸꾹질로 provider 를 끊지 않으려는 것이고, 탐색은 사용자 트래픽이 아니다. 기한이 지난
   *       직후의 탐색이 실패했는데 다시 닫지 않으면, 다음 틱(기본 1분)까지 사용자 요청이 죽은
   *       provider 로 가서 타임아웃을 그대로 문다 — 스펙 §3.1 이 피하려던 바로 그 비용이다.</li>
   *   <li><b>내용 실패(BAD_REQUEST·OUTPUT_INVALID)는 탐색을 포기한다.</b> 가용성 문제가 아니므로
   *       막지는 않되, 기한이 지난 항목으로 남겨 두면 스케줄러가 1분마다 영원히 핑하면서
   *       「latch stays open」이라는 거짓 로그를 남긴다(현실적 발동 조건 = 탐색기의 모델명이 낡아
   *       404 → {@code OUTPUT_INVALID}). 백오프 사다리와 연속 카운터는 <b>보존한다</b> —
   *       가용성 이력을 말해 주는 값인데 내용 실패는 그에 대해 아무것도 말하지 않는다.</li>
   * </ul>
   *
   * @return 이 호출 뒤에도 래치가 열려 있으면 {@code true}
   */
  public boolean recordProbeFailure(
      String feature, String provider, FailureKind kind, Duration retryAfter) {
    State s = states.computeIfAbsent(key(feature, provider), k -> new State());
    synchronized (s) {
      switch (kind) {
        case BAD_REQUEST, OUTPUT_INVALID -> s.openUntil = null;
        case AUTH -> open(s, AUTH_DEADLINE);
        case RATE_LIMIT -> open(s, retryAfter != null ? retryAfter : nextRateLimit(s));
        case TRANSIENT -> open(s, nextTransient(s));
      }
      return s.openUntil != null;
    }
  }

  /** 기한이 지난 항목. 열린 적 있고 기한이 지났으면 탐색 대상이다. 순서는 의미를 갖지 않는다. */
  public List<Probe> dueProbes() {
    Instant now = clock.instant();
    List<Probe> due = new ArrayList<>();
    for (Map.Entry<String, State> e : states.entrySet()) {
      State s = e.getValue();
      if (s.openUntil == null || now.isBefore(s.openUntil)) continue;
      int slash = e.getKey().indexOf('/');
      due.add(new Probe(e.getKey().substring(0, slash), e.getKey().substring(slash + 1)));
    }
    return due;
  }

  private void open(State s, Duration deadline) {
    s.openUntil = clock.instant().plus(deadline);
  }

  private static Duration nextRateLimit(State s) {
    return s.rateLimitBackoff = grow(s.rateLimitBackoff, RATE_LIMIT_BASE, RATE_LIMIT_CAP);
  }

  private static Duration nextTransient(State s) {
    return s.transientBackoff = grow(s.transientBackoff, TRANSIENT_BASE, TRANSIENT_CAP);
  }

  /** 직전 기한의 두 배, 상한까지. 처음이면 base. */
  private static Duration grow(Duration last, Duration base, Duration cap) {
    Duration d = last == null ? base : last.multipliedBy(2);
    return d.compareTo(cap) > 0 ? cap : d;
  }
}
