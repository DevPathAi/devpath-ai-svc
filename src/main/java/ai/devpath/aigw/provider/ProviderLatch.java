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

  private static final class State {
    Instant openUntil;          // null = 닫힘
    Duration lastBackoff;       // 연속 증가용
    int transientRun;
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
      s.lastBackoff = null;
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
          open(s, retryAfter != null ? retryAfter : next(s, RATE_LIMIT_BASE, RATE_LIMIT_CAP));
        }
        case TRANSIENT -> {
          s.transientRun++;
          if (s.transientRun >= TRANSIENT_THRESHOLD) {
            open(s, next(s, TRANSIENT_BASE, TRANSIENT_CAP));
          }
        }
      }
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

  /** 직전 기한의 두 배, 상한까지. 처음이면 base. */
  private static Duration next(State s, Duration base, Duration cap) {
    Duration d = s.lastBackoff == null ? base : s.lastBackoff.multipliedBy(2);
    if (d.compareTo(cap) > 0) d = cap;
    s.lastBackoff = d;
    return d;
  }
}
