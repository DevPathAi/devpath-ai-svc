package ai.devpath.aigw.retention;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** mentor/review와 동일 타입(AnthropicClient) 빈 충돌 회피 위해 빈 이름 분리(retentionAnthropicClient). */
@Configuration
@ConditionalOnProperty(name = "devpath.retention.provider", havingValue = "claude")
public class RetentionClaudeClientConfig {
	@Bean(name = "retentionAnthropicClient")
	public AnthropicClient retentionAnthropicClient() {
		return AnthropicOkHttpClient.fromEnv(); // ANTHROPIC_API_KEY
	}
}
