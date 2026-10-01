package ai.devpath.aigw.provider;

import com.anthropic.client.AnthropicClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 세 기능의 Claude 복구 탐색기. 키가 없으면 AnthropicClient 빈이 없으므로 탐색기도 만들지 않는다
 * (그 경우 Claude 는 애초에 체인에 없어 래치가 열릴 일도 없다).
 *
 * <p>탐색기가 없으면 {@link ProviderProbeScheduler} 가 아무 래치도 닫지 못하고, 기한이 지난 뒤
 * <b>첫 사용자 요청이 탐색을 대신 물게 된다</b> — 스펙 §3.1 이 금지한 동작이다.
 */
@Configuration
@ConditionalOnExpression("'${ANTHROPIC_API_KEY:}' != ''")
public class ClaudeProbeConfig {

  @Bean
  public ProviderProbe reviewClaudeProbe(
      @Qualifier("anthropicClient") ObjectProvider<AnthropicClient> client,
      @Value("${devpath.review.claude-model:claude-sonnet-4-6}") String model) {
    return new ClaudeProviderProbe(ProviderFeatures.REVIEW, client.getObject(), model);
  }

  @Bean
  public ProviderProbe communitySeedClaudeProbe(
      @Qualifier("communitySeedAnthropicClient") ObjectProvider<AnthropicClient> client,
      @Value("${devpath.community-seed.claude-model:claude-haiku-4-5}") String model) {
    return new ClaudeProviderProbe(ProviderFeatures.COMMUNITY_SEED, client.getObject(), model);
  }

  @Bean
  public ProviderProbe retentionClaudeProbe(
      @Qualifier("retentionAnthropicClient") ObjectProvider<AnthropicClient> client,
      @Value("${devpath.retention.claude-model:claude-sonnet-4-6}") String model) {
    return new ClaudeProviderProbe(ProviderFeatures.RETENTION, client.getObject(), model);
  }
}
