package ai.devpath.aigw.community;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * 커뮤니티 시드 클라이언트 단일 진입점 조립. <b>mock 은 체인에 넣지 않는다</b>(스펙 §4.1) —
 * 가짜 답변이 공개 커뮤니티에 남는 것은 실패보다 나쁘다. provider=mock 일 때만 단독으로 쓴다.
 */
@Configuration
public class CommunitySeedClientConfig {

  @Bean
  public AiSeedClient seedClient(
      @Value("${devpath.community-seed.provider:mock}") String provider,
      @Value("${devpath.community-seed.fallback:}") String fallbackCsv,
      @Value("${devpath.community-seed.ollama-base-url:${devpath.ollama.base-url:http://localhost:11434}}")
      String ollamaBaseUrl,
      @Value("${devpath.community-seed.ollama-model:qwen2.5:7b}") String ollamaModel,
      @Value("${devpath.community-seed.ollama-timeout:PT60S}") Duration ollamaTimeout,
      @Value("${devpath.community-seed.claude-model:claude-haiku-4-5}") String claudeModel,
      SeedPromptBuilder prompts, JsonMapper jsonMapper, ProviderLatch latch,
      @Qualifier("communitySeedAnthropicClient")
      ObjectProvider<AnthropicClient> anthropicClientProvider) {

    if ("mock".equals(provider == null ? null : provider.trim())) {
      return new MockSeedClient();
    }

    LinkedHashMap<String, AiSeedClient> available = new LinkedHashMap<>();
    available.put("ollama",
        new OllamaSeedClient(ollamaBaseUrl, ollamaModel, ollamaTimeout, prompts, jsonMapper));
    AnthropicClient anthropic = anthropicClientProvider.getIfAvailable();
    if (anthropic != null) {
      available.put("claude", new ClaudeSeedClient(anthropic, claudeModel, prompts));
    }

    LinkedHashMap<String, AiSeedClient> chain =
        ProviderChain.orderedMap(provider, fallbackCsv, available);
    if (chain.isEmpty()) {
      throw new IllegalStateException(
          "devpath.community-seed.provider=" + provider + " 에 해당하는 가용 provider 가 없다");
    }
    return chain.size() == 1
        ? chain.values().iterator().next()
        : new FallbackAiSeedClient(chain, latch);
  }
}
