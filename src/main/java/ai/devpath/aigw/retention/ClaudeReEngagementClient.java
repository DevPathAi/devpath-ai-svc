package ai.devpath.aigw.retention;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;

public class ClaudeReEngagementClient implements ReEngagementSuggestionClient {

	private final AnthropicClient client;
	private final String model;
	private final ReEngagementPromptBuilder prompts;

	public ClaudeReEngagementClient(
			AnthropicClient client,
			String model,
			ReEngagementPromptBuilder prompts) {
		this.client = client;
		this.model = model;
		this.prompts = prompts;
	}

	@Override
	public String suggest(ReEngagementInput input) {
		MessageCreateParams params = MessageCreateParams.builder()
				.model(model)
				.maxTokens(300L)
				.system(prompts.systemPrompt())
				.addUserMessage(prompts.userContent(input))
				.build();
		try {
			return client.messages().create(params).content().stream()
					.flatMap(cb -> cb.text().stream())
					.map(typed -> typed.text())
					.findFirst()
					.orElseThrow(() -> new ReEngagementGenerationException("Claude 응답이 비어 있습니다", null));
		} catch (AnthropicException e) {
			throw new ReEngagementGenerationException("Claude 재참여 문구 생성 실패", e);
		}
	}

	@Override
	public String providerName() { return "CLAUDE"; }
}
