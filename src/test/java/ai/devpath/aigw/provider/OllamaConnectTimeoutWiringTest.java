package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.devpath.aigw.community.AiSeedClient;
import ai.devpath.aigw.community.CommunitySeedClientConfig;
import ai.devpath.aigw.community.SeedPromptBuilder;
import ai.devpath.aigw.retention.ReEngagementClientConfig;
import ai.devpath.aigw.retention.ReEngagementPromptBuilder;
import ai.devpath.aigw.retention.ReEngagementSuggestionClient;
import ai.devpath.aigw.review.AiReviewClient;
import ai.devpath.aigw.review.ReviewClientConfig;
import ai.devpath.aigw.review.ReviewPromptBuilder;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** 세 기능의 Ollama 클라이언트가 연결 타임아웃을 읽기와 따로 받는지(스펙 2026-10-03 §3.1-6). */
class OllamaConnectTimeoutWiringTest {

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
            ReEngagementClientConfig.class)
        .withPropertyValues(
            "devpath.review.provider=ollama",
            "devpath.community-seed.provider=ollama",
            "devpath.retention.provider=ollama");
  }

  /** DefaultRestClient(Spring 7.0.8)는 팩토리를 clientRequestFactory 필드에 든다(javap 실측). */
  private static int timeout(Object ollamaClient, String field) {
    Object restClient = ReflectionTestUtils.getField(ollamaClient, "restClient");
    Object factory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");
    return (int) ReflectionTestUtils.getField(factory, field);
  }

  @Test
  void connectsWithinThreeSecondsByDefaultWhileReadingForSixty() {
    runner().run(context -> {
      for (Object client : new Object[] {
          context.getBean(AiReviewClient.class),
          context.getBean(AiSeedClient.class),
          context.getBean(ReEngagementSuggestionClient.class)}) {
        assertEquals(3_000, timeout(client, "connectTimeout"), client.getClass().getSimpleName());
        assertEquals(60_000, timeout(client, "readTimeout"), client.getClass().getSimpleName());
      }
    });
  }

  @Test
  void eachFeatureCanOverrideItsConnectTimeout() {
    runner()
        .withPropertyValues(
            "devpath.review.ollama-connect-timeout=PT1S",
            "devpath.community-seed.ollama-connect-timeout=PT2S",
            "devpath.retention.ollama-connect-timeout=PT4S")
        .run(context -> {
          assertEquals(1_000, timeout(context.getBean(AiReviewClient.class), "connectTimeout"));
          assertEquals(2_000, timeout(context.getBean(AiSeedClient.class), "connectTimeout"));
          assertEquals(4_000,
              timeout(context.getBean(ReEngagementSuggestionClient.class), "connectTimeout"));
        });
  }
}
