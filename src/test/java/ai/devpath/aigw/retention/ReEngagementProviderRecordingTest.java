package ai.devpath.aigw.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.devpath.aigw.provider.FailureKind;
import ai.devpath.aigw.provider.ProviderLatch;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/**
 * 스펙 §9 통합 — <b>컨트롤러</b>를 실제 {@link FallbackReEngagementClient} 로 관통시킨다.
 *
 * <p>retention 은 세 기능 중 유일하게 호출 측이 provider 를 기록하지 않는다
 * ({@code ReEngagementController} 는 {@code providerName()} 을 부르지 않는다). 그래서 여기서는
 * 「폴백한 뒤 체인이 말하는 이름」을 직접 못박는다 — 운영에 기록되지는 않지만 같은 계약을 공유하고,
 * 앞으로 호출 측이 기록을 시작하면 그때 바로 맞아야 한다.
 */
class ReEngagementProviderRecordingTest {

  private static final ReEngagementInput INPUT =
      new ReEngagementInput(42L, Instant.parse("2026-09-01T00:00:00Z"), 30, "자바 기초");

  private static final class Stub implements ReEngagementSuggestionClient {
    private final String name;
    private final RuntimeException failure;
    private final String success;

    Stub(String name, RuntimeException failure, String success) {
      this.name = name;
      this.failure = failure;
      this.success = success;
    }

    @Override public String suggest(ReEngagementInput input) {
      if (failure != null) throw failure;
      return success;
    }

    @Override public String providerName() { return name; }
  }

  private static RestClientResponseException status(int code, String reason) {
    return new RestClientResponseException(reason, code, reason, new HttpHeaders(), null, null);
  }

  private static ProviderLatch latch() {
    return new ProviderLatch(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
  }

  private static FallbackReEngagementClient chain(ProviderLatch latch, Stub... stubs) {
    LinkedHashMap<String, ReEngagementSuggestionClient> delegates = new LinkedHashMap<>();
    for (Stub s : stubs) delegates.put(s.providerName().toLowerCase(java.util.Locale.ROOT), s);
    return new FallbackReEngagementClient(delegates, latch);
  }

  @Test
  void controllerServesFromTheFallbackAndTheChainReportsThatProvider() {
    FallbackReEngagementClient client = chain(latch(),
        new Stub("CLAUDE", status(503, "Service Unavailable"), null),
        new Stub("OLLAMA", null, "오랜만이에요 — 자바 기초를 이어가 볼까요?"));

    ReEngagementResult result = new ReEngagementController(client, true).reEngage(INPUT);

    assertEquals("오랜만이에요 — 자바 기초를 이어가 볼까요?", result.message());
    assertEquals("OLLAMA", client.providerName());
  }

  @Test
  void doesNotLeakTheServedProviderToTheNextRequestOnTheSameThread() {
    ProviderLatch latch = latch();
    FallbackReEngagementClient client = chain(latch,
        new Stub("CLAUDE", status(503, "Service Unavailable"), null),
        new Stub("OLLAMA", null, "ok"));
    ReEngagementController controller = new ReEngagementController(client, true);

    controller.reEngage(INPUT);
    assertEquals("OLLAMA", client.providerName());

    // 두 provider 모두 차단 → 1순위(Claude)를 한 번 부른다(스펙 2026-10-03 §3.1-1 규칙 ③).
    // 직전 요청의 OLLAMA 가 남아 있으면 안 된다.
    latch.recordFailure("retention", "claude", FailureKind.AUTH, null);
    latch.recordFailure("retention", "ollama", FailureKind.AUTH, null);
    try {
      controller.reEngage(INPUT);
    } catch (RestClientResponseException expected) {
      // 1순위 스텁의 503 이 그대로 올라온다
    }

    assertEquals("CLAUDE", client.providerName());
  }
}
