package ai.devpath.aigw.retention;

import ai.devpath.aigw.provider.ProviderFailures;
import ai.devpath.aigw.provider.ProviderLatch;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 순서형 폴백 재참여 문구 클라이언트. 래치가 열린 provider 는 호출하지 않고 건너뛴다.
 *
 * <p>문구는 일회성이라 즉시 이어받는다. 체인이 전부 차단됐으면 mock 으로 떨어지지 않고
 * 기존 예외를 던진다(스펙 §4.1 — 가짜 문구가 알림으로 발송되는 것은 실패보다 나쁘다).
 */
public class FallbackReEngagementClient implements ReEngagementSuggestionClient {

	private static final String FEATURE = "retention";

	private final LinkedHashMap<String, ReEngagementSuggestionClient> delegates;
	private final ProviderLatch latch;
	private final ThreadLocal<String> served = new ThreadLocal<>();

	public FallbackReEngagementClient(
			LinkedHashMap<String, ReEngagementSuggestionClient> delegates, ProviderLatch latch) {
		if (delegates == null || delegates.isEmpty()) {
			throw new IllegalArgumentException("delegates must not be empty");
		}
		this.delegates = new LinkedHashMap<>(delegates);
		this.latch = latch;
	}

	@Override
	public String suggest(ReEngagementInput input) {
		RuntimeException last = null;
		for (Map.Entry<String, ReEngagementSuggestionClient> e : delegates.entrySet()) {
			String name = e.getKey();
			if (latch.isOpen(FEATURE, name)) continue;
			try {
				String text = e.getValue().suggest(input);
				latch.recordSuccess(FEATURE, name);
				served.set(name);
				return text;
			} catch (RuntimeException ex) {
				ProviderFailures.Classified c = ProviderFailures.classify(ex);
				latch.recordFailure(FEATURE, name, c.kind(), c.retryAfter());
				last = ex;
			}
		}
		if (last != null) throw last;
		throw new ReEngagementGenerationException("모든 재참여 provider 가 차단 상태입니다", null);
	}

	@Override
	public String providerName() {
		String s = served.get();
		return s != null ? s : delegates.keySet().iterator().next();
	}
}
