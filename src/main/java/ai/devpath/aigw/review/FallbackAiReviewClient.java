package ai.devpath.aigw.review;

import ai.devpath.aigw.provider.ProviderFailures;
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

  private static final String FEATURE = "review";

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
    RuntimeException last = null;
    for (Map.Entry<String, AiReviewClient> e : delegates.entrySet()) {
      String name = e.getKey();
      if (latch.isOpen(FEATURE, name)) continue;
      try {
        ReviewResult result = e.getValue().review(input);
        latch.recordSuccess(FEATURE, name);
        served.set(name);
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
    return s != null ? s : delegates.keySet().iterator().next();
  }
}
