package ai.devpath.aigw.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.aigw.community.AiSeedClient;
import ai.devpath.aigw.community.CommunitySeedClientConfig;
import ai.devpath.aigw.community.MockSeedClient;
import ai.devpath.aigw.community.OllamaSeedClient;
import ai.devpath.aigw.community.SeedPromptBuilder;
import ai.devpath.aigw.retention.ReEngagementClientConfig;
import ai.devpath.aigw.retention.ReEngagementPromptBuilder;
import ai.devpath.aigw.retention.MockReEngagementClient;
import ai.devpath.aigw.retention.OllamaReEngagementClient;
import ai.devpath.aigw.retention.ReEngagementSuggestionClient;
import ai.devpath.aigw.review.AiReviewClient;
import ai.devpath.aigw.review.MockAiReviewClient;
import ai.devpath.aigw.review.OllamaAiReviewClient;
import ai.devpath.aigw.review.ReviewClientConfig;
import ai.devpath.aigw.review.ReviewPromptBuilder;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스펙 §4.1 회귀 잠금. {@code *_FALLBACK=mock} 을 설정해도 mock 이 체인에 들어가지 않아야 한다 —
 * 가짜 산출물이 저장·발송되는 것은 실패보다 나쁘다. 소스 텍스트가 아니라 <b>조립 결과</b>를 단언한다.
 *
 * <p>ANTHROPIC_API_KEY 를 주지 않으므로 Claude 빈은 없고, 체인은 ollama 하나가 된다.
 */
class MockExclusionTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(ProviderLatch.class, () -> new ProviderLatch(Clock.systemUTC()))
        .withBean(JsonMapper.class, JsonMapper::new)
        .withBean(ReviewPromptBuilder.class, ReviewPromptBuilder::new)
        .withBean(SeedPromptBuilder.class, SeedPromptBuilder::new)
        .withBean(ReEngagementPromptBuilder.class, ReEngagementPromptBuilder::new)
        .withUserConfiguration(
            ReviewClientConfig.class, CommunitySeedClientConfig.class,
            ReEngagementClientConfig.class);
  }

  @Test
  void reviewIgnoresAMockFallback() {
    runner()
        .withPropertyValues(
            "devpath.review.provider=ollama", "devpath.review.fallback=mock",
            "devpath.community-seed.provider=ollama", "devpath.retention.provider=ollama")
        .run(context -> {
          assertThat(context).hasNotFailed();
          // mock 이 제외되면 체인 길이 1 -> Ollama 구현 자체. 섞이면 FallbackAiReviewClient 가 된다.
          assertThat(context.getBean(AiReviewClient.class)).isExactlyInstanceOf(
              OllamaAiReviewClient.class);
        });
  }

  @Test
  void communitySeedIgnoresAMockFallback() {
    runner()
        .withPropertyValues(
            "devpath.review.provider=ollama",
            "devpath.community-seed.provider=ollama", "devpath.community-seed.fallback=mock",
            "devpath.retention.provider=ollama")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(AiSeedClient.class)).isExactlyInstanceOf(
              OllamaSeedClient.class);
        });
  }

  @Test
  void retentionIgnoresAMockFallback() {
    runner()
        .withPropertyValues(
            "devpath.review.provider=ollama", "devpath.community-seed.provider=ollama",
            "devpath.retention.provider=ollama", "devpath.retention.fallback=mock")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ReEngagementSuggestionClient.class))
              .isExactlyInstanceOf(OllamaReEngagementClient.class);
        });
  }

  @Test
  void providerMockStillGivesTheMockClientOnItsOwn() {
    // mock 자체를 금지하는 것이 아니다 — 체인에 섞이는 것만 막는다. 개발·CI 는 provider=mock 을 쓴다.
    runner()
        .withPropertyValues(
            "devpath.review.provider=mock",
            "devpath.community-seed.provider=mock",
            "devpath.retention.provider=mock")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(AiReviewClient.class))
              .isExactlyInstanceOf(MockAiReviewClient.class);
          assertThat(context.getBean(AiSeedClient.class))
              .isExactlyInstanceOf(MockSeedClient.class);
          assertThat(context.getBean(ReEngagementSuggestionClient.class))
              .isExactlyInstanceOf(MockReEngagementClient.class);
        });
  }
}
