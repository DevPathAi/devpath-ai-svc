package ai.devpath.aigw.community;

import ai.devpath.aigw.provider.ProviderAttemptPlan;
import ai.devpath.aigw.provider.ProviderFailures;
import ai.devpath.aigw.provider.ProviderFeatures;
import ai.devpath.aigw.provider.ProviderLatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 순서형 폴백 커뮤니티 시드 클라이언트. 래치가 열린 provider 는 호출하지 않고 건너뛴다.
 *
 * <p>시드 답변은 저장되고 공개되므로 어느 provider 가 만들었는지 {@link #providerName()} 으로
 * 기록된다. mock 으로는 떨어지지 않는다(스펙 §4.1).
 *
 * <p>시도 순서와 재시도 예산은 {@link ProviderAttemptPlan} 이 정한다(스펙 2026-10-03 §3.1) — 시드는
 * Kafka 재시도가 없어 한 번의 503 이 그 질문의 답변을 영구히 잃으므로, 넘겨받을 폴백이 없을 때 Claude 에
 * SDK 기본 재시도를 주는 것이 특히 중요하다.
 */
public class FallbackAiSeedClient implements AiSeedClient {

  private static final String FEATURE = ProviderFeatures.COMMUNITY_SEED;

  private final LinkedHashMap<String, AiSeedClient> delegates;
  private final Map<String, AiSeedClient> lastResort;
  private final ProviderLatch latch;
  private final ThreadLocal<String> served = new ThreadLocal<>();

  public FallbackAiSeedClient(
      LinkedHashMap<String, AiSeedClient> delegates, ProviderLatch latch) {
    this(delegates, Map.of(), latch);
  }

  public FallbackAiSeedClient(
      LinkedHashMap<String, AiSeedClient> delegates,
      Map<String, AiSeedClient> lastResort,
      ProviderLatch latch) {
    if (delegates == null || delegates.isEmpty()) {
      throw new IllegalArgumentException("delegates must not be empty");
    }
    this.delegates = new LinkedHashMap<>(delegates);
    this.lastResort = Map.copyOf(lastResort);
    this.latch = latch;
  }

  @Override
  public SeedAnswer generate(SeedInput input) {
    // 들어올 때 비운다 — 풀 워커 스레드에 직전 요청의 값이 남아 있으면 이번 요청의
    // 기록이 그 값을 제 것으로 발행한다(FallbackMentorClient 와 같은 수명 관리).
    served.remove();
    List<ProviderAttemptPlan.Attempt> plan = ProviderAttemptPlan.plan(
        List.copyOf(delegates.keySet()), name -> latch.isOpen(FEATURE, name));
    RuntimeException last = null;
    for (ProviderAttemptPlan.Attempt attempt : plan) {
      String name = attempt.name();
      AiSeedClient delegate = attempt.mode() == ProviderAttemptPlan.Mode.LAST_RESORT
          ? lastResort.getOrDefault(name, delegates.get(name))
          : delegates.get(name);
      // 체인 키(소문자)가 아니라 구현체가 스스로 말하는 이름(대문자)을 기록한다 —
      // 이 값이 그대로 저장·발행되고, 그 자리엔 이미 대문자가 들어 있다.
      // 호출 **전에** 기록하므로 전부 실패해도 마지막으로 시도한 provider 가 남는다.
      served.set(delegate.providerName());
      try {
        SeedAnswer answer = delegate.generate(input);
        latch.recordSuccess(FEATURE, name);
        return answer;
      } catch (RuntimeException ex) {
        ProviderFailures.Classified c = ProviderFailures.classify(ex);
        latch.recordFailure(FEATURE, name, c.kind(), c.retryAfter());
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
