package ai.devpath.aigw.community;

import ai.devpath.aigw.provider.ProviderFailures;
import ai.devpath.aigw.provider.ProviderLatch;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 순서형 폴백 커뮤니티 시드 클라이언트. 래치가 열린 provider 는 호출하지 않고 건너뛴다.
 *
 * <p>시드 답변은 저장되고 공개되므로 어느 provider 가 만들었는지 {@link #providerName()} 으로
 * 기록된다. 체인이 전부 차단됐으면 mock 으로 떨어지지 않고 기존 예외를 던진다(스펙 §4.1).
 */
public class FallbackAiSeedClient implements AiSeedClient {

  private static final String FEATURE = "community-seed";

  private final LinkedHashMap<String, AiSeedClient> delegates;
  private final ProviderLatch latch;
  private final ThreadLocal<String> served = new ThreadLocal<>();

  public FallbackAiSeedClient(
      LinkedHashMap<String, AiSeedClient> delegates, ProviderLatch latch) {
    if (delegates == null || delegates.isEmpty()) {
      throw new IllegalArgumentException("delegates must not be empty");
    }
    this.delegates = new LinkedHashMap<>(delegates);
    this.latch = latch;
  }

  @Override
  public SeedAnswer generate(SeedInput input) {
    RuntimeException last = null;
    for (Map.Entry<String, AiSeedClient> e : delegates.entrySet()) {
      String name = e.getKey();
      if (latch.isOpen(FEATURE, name)) continue;
      try {
        SeedAnswer answer = e.getValue().generate(input);
        latch.recordSuccess(FEATURE, name);
        served.set(name);
        return answer;
      } catch (RuntimeException ex) {
        ProviderFailures.Classified c = ProviderFailures.classify(ex);
        latch.recordFailure(FEATURE, name, c.kind(), c.retryAfter());
        last = ex;
      }
    }
    if (last != null) throw last;
    throw new SeedGenerationException("LLM_ALL_PROVIDERS_BLOCKED",
        "모든 시드 provider 가 차단 상태입니다", null);
  }

  @Override
  public String providerName() {
    String s = served.get();
    return s != null ? s : delegates.keySet().iterator().next();
  }
}
