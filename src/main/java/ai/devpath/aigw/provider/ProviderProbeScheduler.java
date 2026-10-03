package ai.devpath.aigw.provider;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기한이 지난 래치를 배경에서 탐색해 닫는다. <b>사용자 요청을 탐색에 쓰지 않는다</b>(스펙 §3.1) —
 * half-open 탐색을 사용자 요청으로 하면 실패할 때 그 사용자가 지연을 문다. 최소 프롬프트의 크레딧
 * 비용은 무시할 수준이고, 크레딧이 소진된 상태라면 즉시 429 로 떨어져 비용이 아예 없다.
 *
 * <p>{@code @EnableScheduling} 은 {@code AiApplication} 에 이미 있다.
 */
@Component
public class ProviderProbeScheduler {

  private static final Logger log = LoggerFactory.getLogger(ProviderProbeScheduler.class);

  private final ProviderLatch latch;
  private final List<ProviderProbe> probes;

  public ProviderProbeScheduler(ProviderLatch latch, List<ProviderProbe> probes) {
    this.latch = latch;
    this.probes = List.copyOf(probes);
  }

  @Scheduled(fixedDelayString = "${devpath.provider.probe-interval:PT1M}")
  public void runDueProbes() {
    for (ProviderLatch.Probe due : latch.dueProbes()) {
      ProviderProbe probe = find(due);
      if (probe == null) continue;
      try {
        probe.ping();
        latch.recordSuccess(due.feature(), due.provider());
        log.info("provider latch closed by probe: feature={} provider={}",
            due.feature(), due.provider());
      } catch (RuntimeException e) {
        ProviderFailures.Classified c = ProviderFailures.classify(e);
        // recordFailure 가 아니라 recordProbeFailure 다 — 탐색은 사용자 트래픽이 아니라서
        // TRANSIENT 의 「3연속」 게이트를 적용하지 않고, 내용 실패면 탐색을 포기한다.
        boolean stillOpen = latch.recordProbeFailure(
            due.feature(), due.provider(), c.kind(), c.retryAfter());
        if (stillOpen) {
          log.info("provider latch stays open: feature={} provider={} kind={} reason={}",
              due.feature(), due.provider(), c.kind(), e.toString());
        } else {
          // 열려 있다고 적지 않는다 — 실제로 닫혀 있고, 이 항목은 더 이상 탐색하지 않는다.
          log.warn("provider probe gave up (content failure, latch not blocking):"
                  + " feature={} provider={} kind={} reason={}",
              due.feature(), due.provider(), c.kind(), e.toString());
        }
      }
    }
  }

  /**
   * 폴백 자리 provider 의 생존 탐색(스펙 2026-10-03 §3.1-5). 래치가 <b>닫힌</b> 대상만 핑한다 —
   * 열린 것은 {@link #runDueProbes} 가 복구를 맡는다. 실패하면 사용자 요청의 「3연속」 게이트 없이
   * 즉시 연다(다음 요청이 죽은 폴백으로 가서 지연을 물지 않게). 성공은 기록하지 않는다 — 사용자
   * 요청 실패 카운터는 실제 호출에 대한 증거라 핑 하나로 지우지 않는다.
   */
  @Scheduled(fixedDelayString = "${devpath.provider.liveness-interval:PT30S}")
  public void runLivenessProbes() {
    for (ProviderProbe probe : probes) {
      if (!probe.livenessTarget() || latch.isOpen(probe.feature(), probe.provider())) continue;
      try {
        probe.ping();
      } catch (RuntimeException e) {
        ProviderFailures.Classified c = ProviderFailures.classify(e);
        boolean open = latch.recordProbeFailure(
            probe.feature(), probe.provider(), c.kind(), c.retryAfter());
        log.warn("provider liveness probe failed: feature={} provider={} kind={} latchOpen={} reason={}",
            probe.feature(), probe.provider(), c.kind(), open, e.toString());
      }
    }
  }

  private ProviderProbe find(ProviderLatch.Probe due) {
    for (ProviderProbe p : probes) {
      if (p.feature().equals(due.feature()) && p.provider().equals(due.provider())) return p;
    }
    return null;
  }
}
