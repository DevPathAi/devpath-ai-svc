package ai.devpath.aigw.review;

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
 * 코드리뷰 클라이언트 단일 진입점 조립(멘토와 같은 방식). provider + devpath.review.fallback 을
 * 가용 provider 로 필터링해 체인을 만든다.
 *
 * <p><b>mock 은 체인에 넣지 않는다</b>(스펙 §4.1) — 가짜 리뷰가 사용자 기록에 영구히 남는 것은
 * 실패보다 나쁘다. provider=mock 일 때만 단독으로 쓴다(개발·CI).
 */
@Configuration
public class ReviewClientConfig {

  @Bean
  public AiReviewClient reviewClient(
      @Value("${devpath.review.provider:mock}") String provider,
      @Value("${devpath.review.fallback:}") String fallbackCsv,
      @Value("${devpath.review.ollama-base-url:${devpath.ollama.base-url:http://localhost:11434}}")
      String ollamaBaseUrl,
      @Value("${devpath.review.ollama-model:qwen2.5-coder:7b}") String ollamaModel,
      @Value("${devpath.review.ollama-timeout:PT60S}") Duration ollamaTimeout,
      @Value("${devpath.review.ollama-connect-timeout:PT3S}") Duration ollamaConnectTimeout,
      @Value("${devpath.review.claude-model:claude-sonnet-4-6}") String claudeModel,
      ReviewPromptBuilder prompts, JsonMapper jsonMapper, ProviderLatch latch,
      @Qualifier("anthropicClient") ObjectProvider<AnthropicClient> anthropicClientProvider) {

    if ("mock".equals(provider == null ? null : provider.trim())) {
      return new MockAiReviewClient();
    }

    LinkedHashMap<String, AiReviewClient> available = new LinkedHashMap<>();
    available.put("ollama", new OllamaAiReviewClient(
        ollamaBaseUrl, ollamaModel, ollamaConnectTimeout, ollamaTimeout, prompts, jsonMapper));
    AnthropicClient anthropic = anthropicClientProvider.getIfAvailable();
    if (anthropic != null) {
      available.put("claude", new ClaudeAiReviewClient(anthropic, claudeModel, prompts));
    }

    LinkedHashMap<String, AiReviewClient> chain =
        ProviderChain.orderedMap(provider, fallbackCsv, available);
    if (chain.isEmpty()) {
      throw new IllegalStateException(
          "devpath.review.provider=" + provider + " 에 해당하는 가용 provider 가 없다");
    }
    return chain.size() == 1
        ? chain.values().iterator().next()
        : new FallbackAiReviewClient(chain, latch);
  }
}
