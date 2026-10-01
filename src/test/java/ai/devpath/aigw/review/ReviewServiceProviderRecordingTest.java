package ai.devpath.aigw.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.provider.ProviderLatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/**
 * 스펙 §9 통합 — <b>서비스</b>를 실제 {@link FallbackAiReviewClient} 로 관통시켜, 폴백이 일어났을 때
 * {@code ai_code_reviews.provider} 에 저장되는 값이 <b>실제로 응답한 provider</b> 인지 본다.
 *
 * <p>래퍼 단독 테스트({@code FallbackAiReviewClientTest})는 {@code review()} → {@code providerName()}
 * 순으로 불렀는데 운영({@code ReviewService})은 반대 순서였다. 그래서 그 테스트들이 전부 초록인 채로
 * 폴백 시 체인 머리가 기록되는 결함이 남아 있었다 — 이 테스트가 그 순서를 운영과 똑같이 실행한다.
 */
class ReviewServiceProviderRecordingTest {

  private static final class Stub implements AiReviewClient {
    private final String name;
    private final RuntimeException failure;
    private final ReviewResult success;

    Stub(String name, RuntimeException failure, ReviewResult success) {
      this.name = name;
      this.failure = failure;
      this.success = success;
    }

    @Override public ReviewResult review(ReviewInput input) {
      if (failure != null) throw failure;
      return success;
    }

    @Override public String providerName() { return name; }
  }

  private static RestClientResponseException status(int code, String reason) {
    return new RestClientResponseException(reason, code, reason, new HttpHeaders(), null, null);
  }

  @Test
  void persistsTheProviderThatActuallyServedAfterAFallback() {
    ReviewPersistenceService persistence = mock(ReviewPersistenceService.class);
    SandboxClient sandboxClient = mock(SandboxClient.class);
    ReviewResult result = new ReviewResult(72, List.of("ok"), List.of(), List.of());

    LinkedHashMap<String, AiReviewClient> chain = new LinkedHashMap<>();
    chain.put("claude", new Stub("CLAUDE", status(429, "Too Many Requests"), null));
    chain.put("ollama", new Stub("OLLAMA", null, result));
    AiReviewClient client = new FallbackAiReviewClient(chain, new ProviderLatch(
        Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)));

    UUID eventId = UUID.randomUUID();
    ReviewClaim claim = new ReviewClaim(1L, 11L, eventId, UUID.randomUUID());
    when(persistence.claim(eventId, 11L, 42L, 3L, Duration.ofMinutes(5)))
        .thenReturn(Optional.of(claim));
    when(sandboxClient.getSession(11L)).thenReturn(new SandboxSessionView(
        11L, 42L, "PYTHON", 3L, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(persistence.finishDone(any(), any(), any())).thenReturn(true);

    new ReviewService(persistence, sandboxClient, client, Duration.ofMinutes(5))
        .reviewRun(eventId, 11L, 42L, 3L);

    // 체인 머리("claude")도, 소문자 체인 키("ollama")도 아니다 — 구현체가 스스로 말하는 이름이다.
    verify(persistence).finishDone(claim, result, "OLLAMA");
  }

  @Test
  void persistsTheProviderNameInTheSameCasingAsASingleProviderSetup() {
    // 같은 컬럼에 대문자/소문자가 섞이면 운영이 provider 로 묶을 수 없다(스펙 성공 기준 #4).
    ReviewPersistenceService persistence = mock(ReviewPersistenceService.class);
    SandboxClient sandboxClient = mock(SandboxClient.class);
    ReviewResult result = new ReviewResult(50, List.of(), List.of(), List.of());

    LinkedHashMap<String, AiReviewClient> chain = new LinkedHashMap<>();
    chain.put("ollama", new Stub("OLLAMA", null, result));
    chain.put("claude", new Stub("CLAUDE", null, result));
    AiReviewClient client = new FallbackAiReviewClient(chain, new ProviderLatch(
        Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)));

    UUID eventId = UUID.randomUUID();
    ReviewClaim claim = new ReviewClaim(2L, 12L, eventId, UUID.randomUUID());
    when(persistence.claim(eventId, 12L, 42L, 3L, Duration.ofMinutes(5)))
        .thenReturn(Optional.of(claim));
    when(sandboxClient.getSession(12L)).thenReturn(new SandboxSessionView(
        12L, 42L, "PYTHON", 3L, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(persistence.finishDone(any(), any(), any())).thenReturn(true);

    new ReviewService(persistence, sandboxClient, client, Duration.ofMinutes(5))
        .reviewRun(eventId, 12L, 42L, 3L);

    verify(persistence).finishDone(claim, result, "OLLAMA");
  }
}
