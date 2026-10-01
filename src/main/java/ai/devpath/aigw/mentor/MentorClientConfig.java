package ai.devpath.aigw.mentor;

import ai.devpath.aigw.provider.ProviderChain;
import com.anthropic.client.AnthropicClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 멘토 클라이언트 단일 진입점 조립. provider(primary) + devpath.mentor.fallback(순서형 CSV)을
 * 가용 provider로 필터링해 체인을 만든다. 체인 길이 1이면 그 client, 2 이상이면 FallbackMentorClient.
 * claude는 AnthropicClient 빈(키 존재)이 있을 때만 가용.
 */
@Configuration
public class MentorClientConfig {

  @Bean
  public AiMentorClient mentorClient(
      @Value("${devpath.mentor.provider:mock}") String provider,
      @Value("${devpath.mentor.fallback:}") String fallbackCsv,
      @Value("${devpath.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
      @Value("${devpath.mentor.ollama-model:qwen2.5:7b}") String ollamaModel,
      @Value("${devpath.mentor.claude-model:claude-sonnet-4-6}") String claudeModel,
      MentorTimeoutPolicy timeouts, MentorPromptBuilder prompts, JsonMapper jsonMapper,
      @Qualifier("mentorAnthropicClient")
      ObjectProvider<AnthropicClient> anthropicClientProvider) {

    Map<String, AiMentorClient> available = new LinkedHashMap<>();
    available.put("ollama",
        new OllamaMentorClient(
            ollamaBaseUrl, ollamaModel, timeouts.providerTimeout(), prompts, jsonMapper));
    available.put("mock", new MockMentorClient());
    AnthropicClient anthropic = anthropicClientProvider.getIfAvailable();
    if (anthropic != null) {
      available.put("claude", new ClaudeMentorClient(anthropic, claudeModel, prompts));
    }

    List<AiMentorClient> chain = ProviderChain.ordered(provider, fallbackCsv, available);
    if (chain.isEmpty()) {
      chain = List.of(available.get("mock")); // 안전망: 최소 mock
    }
    return chain.size() == 1 ? chain.get(0) : new FallbackMentorClient(chain);
  }
}
