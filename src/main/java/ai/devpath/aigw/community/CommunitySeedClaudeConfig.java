package ai.devpath.aigw.community;

import ai.devpath.aigw.provider.ClaudeClients;
import com.anthropic.client.AnthropicClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * community-seed 용 AnthropicClient 빈. 키는 ANTHROPIC_API_KEY 환경변수(커밋 금지).
 *
 * <p><b>조건은 provider 값이 아니라 키 존재다</b> — provider=ollama + fallback=claude 배치에서도
 * 빈이 필요하다. 빈 이름은 review(anthropicClient)·mentor(mentorAnthropicClient)와 분리한다.
 * SDK 내부 재시도 예산은 <b>체인을 따라간다</b> — 체인이 있을 때만 끈다(리뷰 I4 결정,
 * 근거와 실측은 {@link ai.devpath.aigw.provider.ClaudeClients#maxRetriesFor}).
 */
@Configuration
@ConditionalOnExpression("'${ANTHROPIC_API_KEY:}' != ''")
public class CommunitySeedClaudeConfig {

  @Bean(name = "communitySeedAnthropicClient")
  public AnthropicClient communitySeedAnthropicClient(
      @Value("${ANTHROPIC_API_KEY}") String apiKey,
      @Value("${devpath.community-seed.claude-base-url:https://api.anthropic.com}") String baseUrl,
      @Value("${devpath.community-seed.claude-timeout:PT60S}") Duration timeout,
      @Value("${devpath.community-seed.provider:mock}") String provider,
      @Value("${devpath.community-seed.fallback:}") String fallbackCsv) {
    return ClaudeClients.build(apiKey, baseUrl, timeout, provider, fallbackCsv);
  }
}
