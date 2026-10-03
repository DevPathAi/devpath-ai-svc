package ai.devpath.aigw.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 세 기능의 Ollama 생존·복구 탐색기(스펙 2026-10-03 §3.1-4). <b>Ollama 가 그 기능의 폴백 자리에
 * 있을 때만</b> 만든다 — fallback 이 비어 있는 환경(체인 길이 1)에서는 탐색 트래픽이 0 이다.
 * 주소·모델은 기능별 ClientConfig 와 같은 키를 읽는다(탐색이 실제 폴백과 같은 곳을 본다).
 */
@Configuration
public class OllamaProbeConfig {

  @Bean
  @ConditionalOnExpression("'${devpath.review.provider:mock}'.trim() != 'mock' and "
      + "T(ai.devpath.aigw.provider.ProviderChain).isFallback("
      + "'${devpath.review.provider:mock}', '${devpath.review.fallback:}', 'ollama')")
  public ProviderProbe reviewOllamaProbe(
      @Value("${devpath.review.ollama-base-url:${devpath.ollama.base-url:http://localhost:11434}}")
      String baseUrl,
      @Value("${devpath.review.ollama-model:qwen2.5-coder:7b}") String model) {
    return new OllamaProviderProbe(ProviderFeatures.REVIEW, baseUrl, model);
  }

  @Bean
  @ConditionalOnExpression("'${devpath.community-seed.provider:mock}'.trim() != 'mock' and "
      + "T(ai.devpath.aigw.provider.ProviderChain).isFallback("
      + "'${devpath.community-seed.provider:mock}', '${devpath.community-seed.fallback:}', 'ollama')")
  public ProviderProbe communitySeedOllamaProbe(
      @Value("${devpath.community-seed.ollama-base-url:${devpath.ollama.base-url:http://localhost:11434}}")
      String baseUrl,
      @Value("${devpath.community-seed.ollama-model:qwen2.5:7b}") String model) {
    return new OllamaProviderProbe(ProviderFeatures.COMMUNITY_SEED, baseUrl, model);
  }

  @Bean
  @ConditionalOnExpression("'${devpath.retention.provider:mock}'.trim() != 'mock' and "
      + "T(ai.devpath.aigw.provider.ProviderChain).isFallback("
      + "'${devpath.retention.provider:mock}', '${devpath.retention.fallback:}', 'ollama')")
  public ProviderProbe retentionOllamaProbe(
      @Value("${devpath.retention.ollama-base-url:${devpath.ollama.base-url:http://localhost:11434}}")
      String baseUrl,
      @Value("${devpath.retention.ollama-model:qwen2.5:7b}") String model) {
    return new OllamaProviderProbe(ProviderFeatures.RETENTION, baseUrl, model);
  }
}
