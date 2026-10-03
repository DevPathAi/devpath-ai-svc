package ai.devpath.aigw.retention;

import ai.devpath.aigw.provider.OllamaHttp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * 재참여 문구 생성(Ollama, /api/chat stream:false 자유 텍스트). 스펙 §1 이 지적한 <b>유일한 신규 구현</b> —
 * review·community-seed 는 Ollama 구현이 이미 있었고 조립만 없었다.
 *
 * <p>HTTP 상태 실패는 <b>감싸지 않고 그대로 올린다</b> — {@code ProviderFailures} 가 429·401 을 보고
 * 래치를 판정해야 한다. 응답이 비었을 때만 {@link ReEngagementGenerationException} 을 던진다.
 */
public class OllamaReEngagementClient implements ReEngagementSuggestionClient {

	private final RestClient restClient;
	private final String model;
	private final ReEngagementPromptBuilder prompts;

	public OllamaReEngagementClient(
			String baseUrl, String model, Duration timeout, ReEngagementPromptBuilder prompts) {
		this(baseUrl, model, timeout, timeout, prompts);
	}

	/** 연결과 읽기 타임아웃을 따로 받는다(스펙 2026-10-03 §3.1-6). 운영 조립은 이 생성자를 쓴다. */
	public OllamaReEngagementClient(
			String baseUrl, String model, Duration connectTimeout, Duration readTimeout,
			ReEngagementPromptBuilder prompts) {
		this.restClient = RestClient.builder().baseUrl(baseUrl)
				.requestFactory(OllamaHttp.requestFactory(connectTimeout, readTimeout)).build();
		this.model = model;
		this.prompts = prompts;
	}

	@Override
	public String suggest(ReEngagementInput input) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", List.of(
				Map.of("role", "system", "content", prompts.systemPrompt()),
				Map.of("role", "user", "content", prompts.userContent(input))));
		body.put("stream", false);
		body.put("options", Map.of("temperature", 0.6));

		OllamaChatResponse response = restClient.post().uri("/api/chat").body(body)
				.retrieve().body(OllamaChatResponse.class);

		if (response == null || response.message() == null
				|| response.message().content() == null
				|| response.message().content().isBlank()) {
			throw new ReEngagementGenerationException("Ollama 재참여 문구 응답이 비어 있습니다", null);
		}
		return response.message().content().trim();
	}

	@Override
	public String providerName() { return "OLLAMA"; }

	private record OllamaChatResponse(OllamaMessage message) {}

	private record OllamaMessage(String content) {}
}
