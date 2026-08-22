package ai.devpath.aigw.mentor;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 멘토용 AnthropicClient 빈. 키는 ANTHROPIC_API_KEY 환경변수(커밋 금지).
 * review/ClaudeClientConfig와 동일 타입 충돌을 피하려 빈 이름을 분리(mentorAnthropicClient).
 * ANTHROPIC_API_KEY가 있을 때만 생성 → MentorClientConfig 팩토리가 이 빈 존재 여부로 claude 폴백 포함/제외를
 * 결정한다(무키/CI는 빈 없음 → claude 자동 제외, 부팅 안전).
 */
@Configuration
@ConditionalOnExpression("'${ANTHROPIC_API_KEY:}' != ''")
public class MentorClaudeClientConfig {

  @Bean(name = "mentorAnthropicClient")
  public AnthropicClient mentorAnthropicClient(
      @Value("${ANTHROPIC_API_KEY}") String apiKey,
      @Value("${devpath.mentor.claude-base-url:https://api.anthropic.com}") String baseUrl,
      MentorTimeoutPolicy timeouts) {
    return buildClient(apiKey, baseUrl, timeouts.providerTimeout());
  }

  static AnthropicClient buildClient(String apiKey, String baseUrl, Duration timeout) {
    return AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .timeout(timeout)
        .maxRetries(0)
        .build();
  }
}
