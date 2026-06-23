package ai.devpath.aigw.review;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 운영(provider=claude)에서만 AnthropicClient 빈 제공. 키는 ANTHROPIC_API_KEY 환경변수(커밋 금지). */
@Configuration
@ConditionalOnProperty(name = "devpath.review.provider", havingValue = "claude")
public class ClaudeClientConfig {

  @Bean
  public AnthropicClient anthropicClient() {
    return AnthropicOkHttpClient.fromEnv(); // ANTHROPIC_API_KEY
  }
}
