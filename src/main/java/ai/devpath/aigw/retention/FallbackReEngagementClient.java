package ai.devpath.aigw.retention;

import ai.devpath.aigw.provider.ProviderFailures;
import ai.devpath.aigw.provider.ProviderFeatures;
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

	private static final String FEATURE = ProviderFeatures.RETENTION;

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
		// 들어올 때 비운다 — 풀 워커 스레드에 직전 요청의 값이 남아 있으면 이번 요청의
		// 기록이 그 값을 제 것으로 발행한다(FallbackMentorClient 와 같은 수명 관리).
		served.remove();
		RuntimeException last = null;
		for (Map.Entry<String, ReEngagementSuggestionClient> e : delegates.entrySet()) {
			String name = e.getKey();
			if (latch.isOpen(FEATURE, name)) continue;
			ReEngagementSuggestionClient delegate = e.getValue();
			// 체인 키(소문자)가 아니라 구현체가 스스로 말하는 이름(대문자)을 기록한다 —
			// 이 값이 그대로 저장·발행되고, 그 자리엔 이미 대문자가 들어 있다.
			// 호출 **전에** 기록하므로 전부 실패해도 마지막으로 시도한 provider 가 남는다.
			served.set(delegate.providerName());
			try {
				String text = delegate.suggest(input);
				latch.recordSuccess(FEATURE, name);
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
		// 한 번 읽으면 비운다 — 호출 측이 읽지 않고 끝난 요청의 값이 스레드에 남아
		// 다음 요청의 기록을 오염시키는 것을 막는다(FallbackMentorClient 와 같은 규약).
		served.remove();
		return s != null ? s : delegates.values().iterator().next().providerName();
	}
}
