package ai.devpath.aigw.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.aigw.community.AiSeedClient;
import ai.devpath.aigw.community.CommunitySeedClientConfig;
import ai.devpath.aigw.community.SeedInput;
import ai.devpath.aigw.community.SeedPromptBuilder;
import ai.devpath.aigw.retention.ReEngagementClientConfig;
import ai.devpath.aigw.retention.ReEngagementInput;
import ai.devpath.aigw.retention.ReEngagementPromptBuilder;
import ai.devpath.aigw.retention.ReEngagementSuggestionClient;
import ai.devpath.aigw.review.AiReviewClient;
import ai.devpath.aigw.review.ReviewClientConfig;
import ai.devpath.aigw.review.ReviewInput;
import ai.devpath.aigw.review.ReviewPromptBuilder;
import java.time.Clock;
import java.time.Instant;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기능별 Ollama 엔드포인트 오버라이드 회귀 잠금. 폴백은 GPU 노드의 Ollama 로 보내면서
 * 임베딩·멘토는 CPU Ollama 에 남겨야 한다 — {@code devpath.ollama.base-url} 하나로는 그 분리가 불가능했다.
 *
 * <p><b>소스 텍스트나 프로퍼티 값이 아니라 실제 요청이 도달한 서버로 단언한다</b> — 조립 과정에서
 * 오버라이드가 누락되면 요청은 조용히 공용 주소로 간다({@code OLLAMA_PATH_*} 가 application.yml 에
 * 등록되지 않아 무시됐던 2026-08-17 실측과 같은 실패 양식).
 */
class FeatureScopedOllamaBaseUrlTest {

  /** 공용 Ollama(CPU 노드) 역할. 폴백 요청이 여기로 오면 오버라이드가 안 먹은 것이다. */
  private MockWebServer shared;

  /** 기능별 오버라이드 대상(GPU 노드) 역할. */
  private MockWebServer scoped;

  @BeforeEach
  void start() throws Exception {
    shared = new MockWebServer();
    shared.start();
    scoped = new MockWebServer();
    scoped.start();
  }

  @AfterEach
  void stop() throws Exception {
    shared.shutdown();
    scoped.shutdown();
  }

  private static final ReEngagementInput RETENTION_INPUT = new ReEngagementInput(
      7L, Instant.parse("2026-09-20T00:00:00Z"), 3, "Spring Boot 기초 3/12주");

  private static final SeedInput SEED_INPUT =
      new SeedInput("질문 제목", "질문 본문입니다.");

  private static final ReviewInput REVIEW_INPUT =
      new ReviewInput("python", "print(1)", "1", "", 0);

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
            "devpath.ollama.base-url=" + shared.url("/"),
            "devpath.review.provider=ollama",
            "devpath.community-seed.provider=ollama",
            "devpath.retention.provider=ollama",
            // 오버라이드가 누락돼 요청이 엉뚱한 서버로 가면 그쪽 큐는 비어 있다. 기본 60초를 기다리지
            // 않도록 줄인다 — 잘못된 라우팅이 느린 실패가 아니라 빠른 실패로 드러나야 한다.
            "devpath.review.ollama-timeout=PT2S",
            "devpath.community-seed.ollama-timeout=PT2S",
            "devpath.retention.ollama-timeout=PT2S");
  }

  /** Ollama /api/chat 응답 한 건. {@code content} 는 JSON 문자열 안에 들어가므로 이스케이프한다. */
  private static MockResponse chat(String content) {
    String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"message\":{\"content\":\"" + escaped + "\"}}");
  }

  /** review 는 structured output 이라 content 자체가 CodeReview 스키마 JSON 이다. */
  private static MockResponse reviewChat() {
    return chat("{\"confidence\":80,\"strengths\":[],\"improvements\":[],\"security\":[]}");
  }

  // ---------- 오버라이드를 주면 그 주소로 간다 ----------

  @Test
  void retentionUsesItsOwnBaseUrlWhenOverridden() {
    runner()
        .withPropertyValues("devpath.retention.ollama-base-url=" + scoped.url("/"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          scoped.enqueue(chat("다시 시작해 볼까요?"));

          context.getBean(ReEngagementSuggestionClient.class).suggest(RETENTION_INPUT);

          assertThat(scoped.getRequestCount()).as("오버라이드한 GPU 쪽").isEqualTo(1);
          assertThat(shared.getRequestCount()).as("공용 CPU 쪽").isZero();
        });
  }

  @Test
  void communitySeedUsesItsOwnBaseUrlWhenOverridden() {
    runner()
        .withPropertyValues("devpath.community-seed.ollama-base-url=" + scoped.url("/"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          scoped.enqueue(chat("초안입니다."));

          context.getBean(AiSeedClient.class).generate(SEED_INPUT);

          assertThat(scoped.getRequestCount()).isEqualTo(1);
          assertThat(shared.getRequestCount()).isZero();
        });
  }

  @Test
  void reviewUsesItsOwnBaseUrlWhenOverridden() {
    runner()
        .withPropertyValues("devpath.review.ollama-base-url=" + scoped.url("/"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          scoped.enqueue(reviewChat());

          context.getBean(AiReviewClient.class).review(REVIEW_INPUT);

          assertThat(scoped.getRequestCount()).isEqualTo(1);
          assertThat(shared.getRequestCount()).isZero();
        });
  }

  // ---------- 오버라이드가 없으면 공용 주소를 그대로 쓴다(운영 불변 보장) ----------

  @Test
  void retentionFallsBackToTheSharedBaseUrl() {
    runner().run(context -> {
      assertThat(context).hasNotFailed();
      shared.enqueue(chat("다시 시작해 볼까요?"));

      context.getBean(ReEngagementSuggestionClient.class).suggest(RETENTION_INPUT);

      assertThat(shared.getRequestCount()).isEqualTo(1);
      assertThat(scoped.getRequestCount()).isZero();
    });
  }

  @Test
  void communitySeedFallsBackToTheSharedBaseUrl() {
    runner().run(context -> {
      assertThat(context).hasNotFailed();
      shared.enqueue(chat("초안입니다."));

      context.getBean(AiSeedClient.class).generate(SEED_INPUT);

      assertThat(shared.getRequestCount()).isEqualTo(1);
      assertThat(scoped.getRequestCount()).isZero();
    });
  }

  @Test
  void reviewFallsBackToTheSharedBaseUrl() {
    runner().run(context -> {
      assertThat(context).hasNotFailed();
      shared.enqueue(reviewChat());

      context.getBean(AiReviewClient.class).review(REVIEW_INPUT);

      assertThat(shared.getRequestCount()).isEqualTo(1);
      assertThat(scoped.getRequestCount()).isZero();
    });
  }

  /** 한 기능만 옮겨도 나머지는 공용에 남는다 — 전부-또는-전무가 아니어야 한다. */
  @Test
  void overridingOneFeatureLeavesTheOthersOnTheSharedBaseUrl() {
    runner()
        .withPropertyValues("devpath.retention.ollama-base-url=" + scoped.url("/"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          scoped.enqueue(chat("다시 시작해 볼까요?"));
          shared.enqueue(chat("초안입니다."));

          context.getBean(ReEngagementSuggestionClient.class).suggest(RETENTION_INPUT);
          context.getBean(AiSeedClient.class).generate(SEED_INPUT);

          assertThat(scoped.getRequestCount()).as("retention 만 옮겨짐").isEqualTo(1);
          assertThat(shared.getRequestCount()).as("seed 는 공용에 남음").isEqualTo(1);
        });
  }
}
