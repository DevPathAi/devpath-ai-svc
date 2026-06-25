package ai.devpath.aigw.mentor;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 운영 멘토(devpath.mentor.provider=claude)용 AnthropicClient 빈. 키는 ANTHROPIC_API_KEY 환경변수(커밋 금지).
 * review/ClaudeClientConfig(review.provider=claude)와 동일 타입 충돌을 피하려 빈 이름을 분리(mentorAnthropicClient).
 * ClaudeMentorClient는 @Qualifier로 이 빈을 명시 주입한다. CI/test는 mock provider라 두 config 모두 비활성.
 */
@Configuration
@ConditionalOnProperty(name = "devpath.mentor.provider", havingValue = "claude")
public class MentorClaudeClientConfig {

  @Bean(name = "mentorAnthropicClient")
  public AnthropicClient mentorAnthropicClient() {
    return AnthropicOkHttpClient.fromEnv(); // ANTHROPIC_API_KEY
  }
}
