package ai.devpath.aigw.retention;

import ai.devpath.aigw.provider.ProviderChain;
import ai.devpath.aigw.provider.ProviderLatch;
import com.anthropic.client.AnthropicClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 재참여 문구 클라이언트 단일 진입점 조립. <b>mock 은 체인에 넣지 않는다</b>(스펙 §4.1) —
 * 가짜 문구가 알림으로 발송되는 것은 실패보다 나쁘다. provider=mock 일 때만 단독으로 쓴다.
 */
@Configuration
public class ReEngagementClientConfig {

	@Bean
	public ReEngagementSuggestionClient reEngagementClient(
			@Value("${devpath.retention.provider:mock}") String provider,
			@Value("${devpath.retention.fallback:}") String fallbackCsv,
			@Value("${devpath.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
			@Value("${devpath.retention.ollama-model:qwen2.5:7b}") String ollamaModel,
			@Value("${devpath.retention.ollama-timeout:PT60S}") Duration ollamaTimeout,
			@Value("${devpath.retention.claude-model:claude-sonnet-4-6}") String claudeModel,
			ReEngagementPromptBuilder prompts, ProviderLatch latch,
			@Qualifier("retentionAnthropicClient")
			ObjectProvider<AnthropicClient> anthropicClientProvider) {

		if ("mock".equals(provider == null ? null : provider.trim())) {
			return new MockReEngagementClient();
		}

		LinkedHashMap<String, ReEngagementSuggestionClient> available = new LinkedHashMap<>();
		available.put("ollama",
				new OllamaReEngagementClient(ollamaBaseUrl, ollamaModel, ollamaTimeout, prompts));
		AnthropicClient anthropic = anthropicClientProvider.getIfAvailable();
		if (anthropic != null) {
			available.put("claude",
					new ClaudeReEngagementClient(anthropic, claudeModel, prompts));
		}

		LinkedHashMap<String, ReEngagementSuggestionClient> chain =
				ProviderChain.orderedMap(provider, fallbackCsv, available);
		if (chain.isEmpty()) {
			throw new IllegalStateException(
					"devpath.retention.provider=" + provider + " 에 해당하는 가용 provider 가 없다");
		}
		return chain.size() == 1
				? chain.values().iterator().next()
				: new FallbackReEngagementClient(chain, latch);
	}
}
