package ai.devpath.aigw.retention;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * retention 용 AnthropicClient 빈. <b>조건은 provider 값이 아니라 키 존재다</b> —
 * provider=ollama + fallback=claude 배치에서도 빈이 필요하다.
 * 빈 이름은 mentor/review 와 분리한다(retentionAnthropicClient).
 * SDK 내부 재시도는 끈다: 429 를 삼키면 ProviderLatch 의 rate_limit 판정이 왜곡된다.
 */
@Configuration
@ConditionalOnExpression("'${ANTHROPIC_API_KEY:}' != ''")
public class RetentionClaudeClientConfig {

	@Bean(name = "retentionAnthropicClient")
	public AnthropicClient retentionAnthropicClient(
			@Value("${ANTHROPIC_API_KEY}") String apiKey,
			@Value("${devpath.retention.claude-base-url:https://api.anthropic.com}") String baseUrl,
			@Value("${devpath.retention.claude-timeout:PT60S}") Duration timeout) {
		return AnthropicOkHttpClient.builder()
				.apiKey(apiKey)
				.baseUrl(baseUrl)
				.timeout(timeout)
				.maxRetries(0)
				.build();
	}
}
