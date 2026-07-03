package ai.devpath.aigw.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내부 서비스 간 호출(notification-svc → ai-svc). /ai/** 는 SecurityConfig에서 permitAll(무인증 내부 경로). */
@RestController
@RequestMapping("/ai")
public class ReEngagementController {

	private final ReEngagementSuggestionClient client;
	private final boolean enabled;

	public ReEngagementController(ReEngagementSuggestionClient client,
			@Value("${devpath.retention.enabled:true}") boolean enabled) {
		this.client = client;
		this.enabled = enabled;
	}

	@PostMapping("/re-engagement")
	public ReEngagementResult reEngage(@RequestBody ReEngagementInput input) {
		if (!enabled) {
			// kill-switch: 비활성 시에도 호출측이 폴백을 쓰도록 예외 대신 빈 문자열 반환하지 않고,
			// 호출측(StagnationConsumer)이 폴백 문구를 쓰게 명확히 실패시킨다.
			throw new ReEngagementGenerationException("re-engagement disabled", null);
		}
		return new ReEngagementResult(client.suggest(input));
	}
}
