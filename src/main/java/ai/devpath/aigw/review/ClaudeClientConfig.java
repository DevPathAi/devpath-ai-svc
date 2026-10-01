package ai.devpath.aigw.review;

import ai.devpath.aigw.provider.ClaudeClients;
import com.anthropic.client.AnthropicClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * review 용 AnthropicClient 빈. 키는 ANTHROPIC_API_KEY 환경변수(커밋 금지).
 *
 * <p><b>조건은 provider 값이 아니라 키 존재다</b> — provider=ollama + fallback=claude 처럼
 * Claude 가 주가 아닌 배치에서도 빈이 필요하다(멘토와 동일한 방식).
 * SDK 내부 재시도 예산은 <b>체인을 따라간다</b> — 체인이 있을 때만 끈다(리뷰 I4 결정,
 * 근거와 실측은 {@link ai.devpath.aigw.provider.ClaudeClients#maxRetriesFor}).
 */
@Configuration
@ConditionalOnExpression("'${ANTHROPIC_API_KEY:}' != ''")
public class ClaudeClientConfig {

  @Bean
  public AnthropicClient anthropicClient(
      @Value("${ANTHROPIC_API_KEY}") String apiKey,
      @Value("${devpath.review.claude-base-url:https://api.anthropic.com}") String baseUrl,
      @Value("${devpath.review.claude-timeout:PT60S}") Duration timeout,
      @Value("${devpath.review.provider:mock}") String provider,
      @Value("${devpath.review.fallback:}") String fallbackCsv) {
    return ClaudeClients.build(apiKey, baseUrl, timeout, provider, fallbackCsv);
  }
}
