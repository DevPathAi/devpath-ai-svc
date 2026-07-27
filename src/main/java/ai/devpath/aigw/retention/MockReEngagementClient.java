package ai.devpath.aigw.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 고정 문구(테스트·로컬·LLM 실패 폴백 공용). 요약이 있으면 살짝 개인화. */
@Component
@ConditionalOnProperty(name = "devpath.retention.provider", havingValue = "mock", matchIfMissing = true)
public class MockReEngagementClient implements ReEngagementSuggestionClient {

	@Override
	public String suggest(ReEngagementInput input) {
		if (input.currentLearningPathSummary() != null && !input.currentLearningPathSummary().isBlank()) {
			return "오랜만이에요! " + input.currentLearningPathSummary() + " 학습을 이어가 볼까요? 오늘 한 걸음이면 충분해요.";
		}
		return "오랜만이에요! 다시 학습을 시작해볼까요? 오늘 한 걸음이면 충분해요.";
	}

	@Override
	public String providerName() { return "MOCK"; }
}
