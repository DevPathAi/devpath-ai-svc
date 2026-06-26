package ai.devpath.aigw.community;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 운영 커뮤니티 시드 클라이언트(Anthropic Claude, 비스트리밍 자유 텍스트).
 * 시드는 "방향 제시 수준 초안"이라 Haiku급 모델 사용(설계 §11). 인젝션 방어는 SeedPromptBuilder.
 * 키(ANTHROPIC_API_KEY)는 SDK가 환경변수로 읽는다. 빈 이름은 review/mentor와 분리(communitySeedAnthropicClient).
 */
@Component
@ConditionalOnProperty(name = "devpath.community-seed.provider", havingValue = "claude")
public class ClaudeSeedClient implements AiSeedClient {

  private final AnthropicClient client;
  private final String model;
  private final SeedPromptBuilder prompts;

  public ClaudeSeedClient(
      @Qualifier("communitySeedAnthropicClient") AnthropicClient client,
      @Value("${devpath.community-seed.claude-model:claude-haiku-4-5}") String model,
      SeedPromptBuilder prompts) {
    this.client = client;
    this.model = model;
    this.prompts = prompts;
  }

  @Override
  public SeedAnswer generate(SeedInput input) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(1000L)
        .system(prompts.systemPrompt())
        .addUserMessage(prompts.userContent(input))
        .build();
    try {
      String content = client.messages().create(params).content().stream()
          .flatMap(cb -> cb.text().stream())
          .map(typed -> typed.text())
          .reduce("", String::concat);
      if (content.isBlank()) {
        throw new SeedGenerationException("LLM_FAILED", "Claude seed 응답이 비어 있습니다", null);
      }
      return new SeedAnswer(content);
    } catch (AnthropicException e) {
      throw new SeedGenerationException("LLM_FAILED", "Claude seed 호출 실패", e);
    }
  }

  @Override
  public String providerName() { return "CLAUDE"; }
}
