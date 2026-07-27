package ai.devpath.aigw.retention;

import org.springframework.stereotype.Component;

@Component
public class ReEngagementPromptBuilder {

	public String systemPrompt() {
		return "너는 학습 플랫폼의 따뜻한 학습 코치다. 3일간 학습하지 않은 사용자에게 부담 없이 다시 시작하도록 격려하는 "
				+ "1~2문장의 짧은 한국어 알림 문구를 만든다. 죄책감을 주지 말고, 구체적이고 실행 가능한 작은 다음 걸음을 제안한다.";
	}

	public String userContent(ReEngagementInput input) {
		String summary = (input.currentLearningPathSummary() == null || input.currentLearningPathSummary().isBlank())
				? "(현재 활성 학습경로 정보 없음)" : input.currentLearningPathSummary();
		return "미활동 일수: " + input.daysInactive() + "일\n현재 학습경로: " + summary
				+ "\n\n위 맥락에 맞는 재참여 알림 문구 1~2문장만 출력해라(다른 설명 없이).";
	}
}
