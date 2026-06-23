package ai.devpath.aigw.review;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 운영 코드리뷰(Anthropic Claude, 구조화 출력으로 ReviewResult 스키마 강제).
 * 키(ANTHROPIC_API_KEY)는 SDK가 환경변수로 읽는다. 키 없으면 호출 시 SDK가 인증 오류 → ReviewService가 FAILED 처리.
 */
@Component
@ConditionalOnProperty(name = "devpath.review.provider", havingValue = "claude")
public class ClaudeAiReviewClient implements AiReviewClient {

  private final AnthropicClient client;
  private final String model;
  private final ReviewPromptBuilder prompts;

  public ClaudeAiReviewClient(
      AnthropicClient client,
      @Value("${devpath.review.claude-model:claude-sonnet-4-6}") String model,
      ReviewPromptBuilder prompts) {
    this.client = client;
    this.model = model;
    this.prompts = prompts;
  }

  @Override
  public ReviewResult review(ReviewInput input) {
    StructuredMessageCreateParams<ReviewResult> params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(2000L)
        .system(prompts.systemPrompt())
        .addUserMessage(prompts.userContent(input))
        .outputConfig(ReviewResult.class) // 응답을 ReviewResult 스키마로 제약(인젝션 방어 보강)
        .build();
    try {
      return client.messages().create(params).content().stream()
          .flatMap(cb -> cb.text().stream())
          .map(typed -> typed.text())
          .findFirst()
          .orElseThrow(() -> new PermanentReviewException("PARSE_FAILED", "Claude 응답이 비어 있습니다", null));
    } catch (RateLimitException e) {
      throw new TransientReviewException("LLM_RATELIMIT", "Claude 429", e);
    } catch (InternalServerException e) {
      throw new TransientReviewException("LLM_5XX", "Claude 5xx", e);
    } catch (AnthropicIoException e) {
      throw new TransientReviewException("LLM_TIMEOUT", "Claude io", e);
    } catch (AnthropicRetryableException e) {
      throw new TransientReviewException("LLM_5XX", "Claude retryable", e);
    } catch (AnthropicException e) {
      throw new PermanentReviewException("PARSE_FAILED", "Claude error", e);
    }
  }

  @Override
  public String providerName() {
    return "CLAUDE";
  }
}
