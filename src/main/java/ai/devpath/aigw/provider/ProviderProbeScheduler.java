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
        latch.recordFailure(due.feature(), due.provider(), c.kind(), c.retryAfter());
        log.info("provider latch stays open: feature={} provider={} kind={} reason={}",
            due.feature(), due.provider(), c.kind(), e.toString());
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
