package ai.devpath.aigw.review;

import ai.devpath.aigw.provider.ProviderFailures;
import ai.devpath.aigw.provider.ProviderFeatures;
import ai.devpath.aigw.provider.ProviderLatch;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 순서형 폴백 코드리뷰 클라이언트. 래치가 열린 provider 는 <b>호출하지 않고</b> 건너뛴다.
 *
 * <p>리뷰는 요청/응답이라 멘토의 「토큰 방출 뒤 전환 불가」 제약이 없다 — 실패하면 항상 다음으로 간다.
 * 체인이 전부 차단됐으면 review 의 기존 예외를 던진다(스펙 §4.1: mock 으로 떨어지지 않는다).
 */
public class FallbackAiReviewClient implements AiReviewClient {

  private static final String FEATURE = ProviderFeatures.REVIEW;

  private final LinkedHashMap<String, AiReviewClient> delegates;
  private final ProviderLatch latch;
  private final ThreadLocal<String> served = new ThreadLocal<>();

  public FallbackAiReviewClient(
      LinkedHashMap<String, AiReviewClient> delegates, ProviderLatch latch) {
    if (delegates == null || delegates.isEmpty()) {
      throw new IllegalArgumentException("delegates must not be empty");
    }
    this.delegates = new LinkedHashMap<>(delegates);
    this.latch = latch;
  }

  @Override
  public ReviewResult review(ReviewInput input) {
    // 들어올 때 비운다 — 풀 워커 스레드에 직전 요청의 값이 남아 있으면 이번 요청의
    // 기록이 그 값을 제 것으로 발행한다(FallbackMentorClient 와 같은 수명 관리).
    served.remove();
    RuntimeException last = null;
    for (Map.Entry<String, AiReviewClient> e : delegates.entrySet()) {
      String name = e.getKey();
      if (latch.isOpen(FEATURE, name)) continue;
      AiReviewClient delegate = e.getValue();
      // 체인 키(소문자)가 아니라 구현체가 스스로 말하는 이름(대문자)을 기록한다 —
      // 이 값이 그대로 저장·발행되고, 그 자리엔 이미 대문자가 들어 있다.
      // 호출 **전에** 기록하므로 전부 실패해도 마지막으로 시도한 provider 가 남는다.
      served.set(delegate.providerName());
      try {
        ReviewResult result = delegate.review(input);
        latch.recordSuccess(FEATURE, name);
        return result;
      } catch (RuntimeException ex) {
        ProviderFailures.Classified c = ProviderFailures.classify(ex);
        latch.recordFailure(FEATURE, name, c.kind(), c.retryAfter());
        last = ex;
      }
    }
    if (last != null) throw last;
    throw new TransientReviewException("LLM_ALL_PROVIDERS_BLOCKED",
        "모든 리뷰 provider 가 차단 상태입니다", null);
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
