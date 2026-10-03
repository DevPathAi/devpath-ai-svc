package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.aigw.community.AiSeedClient;
import ai.devpath.aigw.community.CommunitySeedClaudeConfig;
import ai.devpath.aigw.community.CommunitySeedClientConfig;
import ai.devpath.aigw.community.SeedInput;
import ai.devpath.aigw.community.SeedPromptBuilder;
import ai.devpath.aigw.retention.ReEngagementClientConfig;
import ai.devpath.aigw.retention.ReEngagementInput;
import ai.devpath.aigw.retention.ReEngagementPromptBuilder;
import ai.devpath.aigw.retention.ReEngagementSuggestionClient;
import ai.devpath.aigw.retention.RetentionClaudeClientConfig;
import ai.devpath.aigw.review.AiReviewClient;
import ai.devpath.aigw.review.ClaudeClientConfig;
import ai.devpath.aigw.review.ReviewClientConfig;
import ai.devpath.aigw.review.ReviewInput;
import ai.devpath.aigw.review.ReviewPromptBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스펙 2026-10-03 §2 의 성공 기준을 실제 조립으로 증명한다: 폴백(Ollama)이 죽어 있으면 Claude 는 폴백을
 * 끈 상태와 같은 재시도 예산을 받는다. Claude 목이 처음 500, 다음 200 을 주면 요청이 <b>성공</b>해야 하고
 * (재시도 0 이면 실패한다), 생존 탐색이 돌기 전(폴백이 살아 보임)에는 Claude 가 한 번에 실패를 넘긴다.
 */
class FallbackParityTest {

  private static String message(String text) {
    String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
    return """
        {"id":"msg_parity","type":"message","role":"assistant","model":"claude-sonnet-4-6",
         "content":[{"type":"text","text":"%s"}],"stop_reason":"end_turn","stop_sequence":null,
         "usage":{"input_tokens":1,"output_tokens":1}}
        """.formatted(escaped);
  }

  private static final ReviewInput REVIEW_INPUT = new ReviewInput("python", "print(1)", "1", "", 0);
  private static final SeedInput SEED_INPUT = new SeedInput("질문 제목", "질문 본문입니다.");
  private static final ReEngagementInput RETENTION_INPUT = new ReEngagementInput(
      7L, Instant.parse("2026-09-20T00:00:00Z"), 3, "Spring Boot 기초 3/12주");

  private MockWebServer claude;
  private String deadOllama;

  @BeforeEach
  void start() throws Exception {
    claude = new MockWebServer();
    claude.start();
    MockWebServer ollama = new MockWebServer();
    ollama.start();
    deadOllama = ollama.url("/").toString();
    ollama.shutdown();   // 포트를 닫아 연결 거부 = 회수된 GPU 노드
  }

  @AfterEach
  void stop() throws Exception {
    claude.shutdown();
  }

  private ApplicationContextRunner runner() {
    String claudeUrl = claude.url("/").toString();
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
            ClaudeClientConfig.class, CommunitySeedClaudeConfig.class, RetentionClaudeClientConfig.class,
            ReviewClientConfig.class, CommunitySeedClientConfig.class, ReEngagementClientConfig.class,
            OllamaProbeConfig.class)
        .withPropertyValues(
            "ANTHROPIC_API_KEY=sk-test",
            "devpath.review.provider=claude", "devpath.review.fallback=ollama",
            "devpath.review.claude-base-url=" + claudeUrl,
            "devpath.review.ollama-base-url=" + deadOllama,
            "devpath.review.claude-timeout=PT5S",
            "devpath.community-seed.provider=claude", "devpath.community-seed.fallback=ollama",
            "devpath.community-seed.claude-base-url=" + claudeUrl,
            "devpath.community-seed.ollama-base-url=" + deadOllama,
            "devpath.community-seed.claude-timeout=PT5S",
            "devpath.retention.provider=claude", "devpath.retention.fallback=ollama",
            "devpath.retention.claude-base-url=" + claudeUrl,
            "devpath.retention.ollama-base-url=" + deadOllama,
            "devpath.retention.claude-timeout=PT5S");
  }

  private static void runLiveness(ApplicationContext context) {
    new ProviderProbeScheduler(context.getBean(ProviderLatch.class),
        List.copyOf(context.getBeansOfType(ProviderProbe.class).values())).runLivenessProbes();
  }

  @Test
  void retentionGetsTheSdkRetryBudgetOnceTheDeadFallbackIsDetected() {
    runner().run(context -> {
      runLiveness(context);
      assertTrue(context.getBean(ProviderLatch.class).isOpen("retention", "ollama"));
      claude.enqueue(new MockResponse().setResponseCode(500));
      claude.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
          .setBody(message("다시 시작해 볼까요?")));

      assertEquals("다시 시작해 볼까요?",
          context.getBean(ReEngagementSuggestionClient.class).suggest(RETENTION_INPUT));
      assertEquals(2, claude.getRequestCount());
    });
  }

  @Test
  void beforeDetectionClaudeFailsFastToTheFallback() {
    // 대조군: 생존 탐색 전에는 폴백이 살아 보이므로 Claude 는 재시도 0 으로 한 번에 넘긴다.
    runner().run(context -> {
      claude.enqueue(new MockResponse().setResponseCode(500));

      assertThrows(RuntimeException.class,
          () -> context.getBean(ReEngagementSuggestionClient.class).suggest(RETENTION_INPUT));
      assertEquals(1, claude.getRequestCount());
    });
  }

  @Test
  void everythingBlockedStillCallsClaude() {
    runner().run(context -> {
      runLiveness(context);
      context.getBean(ProviderLatch.class)
          .recordFailure("retention", "claude", FailureKind.RATE_LIMIT, null);
      claude.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
          .setBody(message("돌아오셨네요")));

      assertEquals("돌아오셨네요",
          context.getBean(ReEngagementSuggestionClient.class).suggest(RETENTION_INPUT));
    });
  }

  @Test
  void reviewGetsTheSdkRetryBudgetOnceTheDeadFallbackIsDetected() {
    runner().run(context -> {
      runLiveness(context);
      claude.enqueue(new MockResponse().setResponseCode(500));
      claude.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
          message("{\"confidence\":80,\"strengths\":[],\"improvements\":[],\"security\":[]}")));

      assertEquals(80, context.getBean(AiReviewClient.class).review(REVIEW_INPUT).confidence());
      assertEquals(2, claude.getRequestCount());
    });
  }

  @Test
  void communitySeedGetsTheSdkRetryBudgetOnceTheDeadFallbackIsDetected() {
    runner().run(context -> {
      runLiveness(context);
      claude.enqueue(new MockResponse().setResponseCode(500));
      claude.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
          .setBody(message("이렇게 접근해 보세요.")));

      context.getBean(AiSeedClient.class).generate(SEED_INPUT);
      assertEquals(2, claude.getRequestCount());
    });
  }
}
