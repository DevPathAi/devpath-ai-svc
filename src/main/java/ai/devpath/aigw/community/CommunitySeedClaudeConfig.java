package ai.devpath.aigw.community;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 운영(community-seed.provider=claude)에서만 AnthropicClient 빈 제공. 키는 ANTHROPIC_API_KEY 환경변수(커밋 금지).
 * review/ClaudeClientConfig(anthropicClient)·mentor(mentorAnthropicClient)와 빈 이름 충돌 회피.
 */
@Configuration
@ConditionalOnProperty(name = "devpath.community-seed.provider", havingValue = "claude")
public class CommunitySeedClaudeConfig {

  @Bean(name = "communitySeedAnthropicClient")
  public AnthropicClient communitySeedAnthropicClient() {
    return AnthropicOkHttpClient.fromEnv(); // ANTHROPIC_API_KEY
  }
}
