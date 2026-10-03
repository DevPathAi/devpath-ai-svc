package ai.devpath.aigw.review;

import ai.devpath.aigw.provider.ProviderAttemptPlan;
import ai.devpath.aigw.provider.ProviderFailures;
import ai.devpath.aigw.provider.ProviderFeatures;
import ai.devpath.aigw.provider.ProviderLatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 순서형 폴백 코드리뷰 클라이언트. 래치가 열린 provider 는 <b>호출하지 않고</b> 건너뛴다.
 *
 * <p>리뷰는 요청/응답이라 멘토의 「토큰 방출 뒤 전환 불가」 제약이 없다 — 실패하면 항상 다음으로 간다.
 *
 * <p>시도 순서와 각 시도의 재시도 예산은 {@link ProviderAttemptPlan} 이 정한다(스펙 2026-10-03 §3.1):
 * 뒤에 쓸 수 있는 provider 가 남아 있으면 빠른 구현체(재시도 0)로 실패를 바로 넘기고, 남아 있지 않으면
 * {@code lastResort} 구현체(SDK 기본 재시도)를 쓴다. 전부 막혔으면 1순위를 래치와 무관하게 한 번 부른다 —
 * 폴백을 끈 상태(체인 길이 1)가 매번 Claude 를 부르는 것과 같아진다.
 */
public class FallbackAiReviewClient implements AiReviewClient {

  private static final String FEATURE = ProviderFeatures.REVIEW;

  private final LinkedHashMap<String, AiReviewClient> delegates;
  private final Map<String, AiReviewClient> lastResort;
  private final ProviderLatch latch;
  private final ThreadLocal<String> served = new ThreadLocal<>();

  public FallbackAiReviewClient(
      LinkedHashMap<String, AiReviewClient> delegates, ProviderLatch latch) {
    this(delegates, Map.of(), latch);
  }

  public FallbackAiReviewClient(
      LinkedHashMap<String, AiReviewClient> delegates,
      Map<String, AiReviewClient> lastResort,
      ProviderLatch latch) {
    if (delegates == null || delegates.isEmpty()) {
      throw new IllegalArgumentException("delegates must not be empty");
    }
    this.delegates = new LinkedHashMap<>(delegates);
    this.lastResort = Map.copyOf(lastResort);
    this.latch = latch;
  }

  @Override
  public ReviewResult review(ReviewInput input) {
    // 들어올 때 비운다 — 풀 워커 스레드에 직전 요청의 값이 남아 있으면 이번 요청의
    // 기록이 그 값을 제 것으로 발행한다(FallbackMentorClient 와 같은 수명 관리).
    served.remove();
    List<ProviderAttemptPlan.Attempt> plan = ProviderAttemptPlan.plan(
        List.copyOf(delegates.keySet()), name -> latch.isOpen(FEATURE, name));
    RuntimeException last = null;
    for (ProviderAttemptPlan.Attempt attempt : plan) {
      String name = attempt.name();
      AiReviewClient delegate = attempt.mode() == ProviderAttemptPlan.Mode.LAST_RESORT
          ? lastResort.getOrDefault(name, delegates.get(name))
          : delegates.get(name);
      // 체인 키(소문자)가 아니라 구현체가 스스로 말하는 이름(대문자)을 기록한다 —
      // 이 값이 그대로 저장·발행되고, 그 자리엔 이미 대문자가 들어 있다.
      // 호출 **전에** 기록하므로 전부 실패해도 마지막으로 시도한 provider 가 남는다.
      served.set(delegate.providerName());
      try {
        ReviewResult result = delegate.review(input);
        latch.recordSuccess(FEATURE, name);
        return result;
      } catch (RuntimeException ex) {
        // 전부 막혀 래치를 무시하고 부른 시도(forced)의 실패는 다시 기록하지 않는다 — 이미 열린
        // 래치의 사다리만 키워, 폴백이 돌아온 뒤에도 회복된 1순위를 오래 건너뛰게 된다.
        if (!attempt.forced()) {
          ProviderFailures.Classified c = ProviderFailures.classify(ex);
          latch.recordFailure(FEATURE, name, c.kind(), c.retryAfter());
        }
        last = ex;
      }
    }
    // 계획은 비지 않는다(전부 막혔어도 1순위 1회) — 여기 왔다면 모든 시도가 실패했다.
    throw last;
  }

  @Override
  public String providerName() {
    String s = served.get();
    // 한 번 읽으면 비운다 — 호출 측이 읽지 않고 끝난 요청의 값이 스레드에 남아
    // 다음 요청의 기록을 오염시키는 것을 막는다(FallbackMentorClient 와 같은 규약).
    served.remove();
    return s != null ? s : delegates.values().iterator().next().providerName();
  }
}
