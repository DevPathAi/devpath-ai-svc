package ai.devpath.aigw.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.aigw.provider.FailureKind;
import ai.devpath.aigw.provider.ProviderLatch;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class FallbackAiReviewClientTest {

  private static final ReviewInput INPUT = null; // 스텁은 입력을 보지 않는다

  /** confidence 로 어느 provider 가 응답했는지 구분한다(ReviewResult 에 summary 가 없다). */
  private static ReviewResult result(int confidence) {
    return new ReviewResult(confidence, List.of(), List.of(), List.of());
  }

  private static final class Stub implements AiReviewClient {
    private final String name;
    private final RuntimeException failure;
    private final ReviewResult success;
    int calls;

    Stub(String name, RuntimeException failure, ReviewResult success) {
      this.name = name;
      this.failure = failure;
      this.success = success;
    }

    @Override public ReviewResult review(ReviewInput input) {
      calls++;
      if (failure != null) throw failure;
      return success;
    }

    @Override public String providerName() { return name; }
  }

  private static RestClientResponseException status(int code, String reason) {
    return new RestClientResponseException(reason, code, reason, new HttpHeaders(), null, null);
  }

  private ProviderLatch latch() {
    return new ProviderLatch(
        Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
  }

  private FallbackAiReviewClient chain(ProviderLatch latch, Stub... stubs) {
    LinkedHashMap<String, AiReviewClient> delegates = new LinkedHashMap<>();
    for (Stub s : stubs) delegates.put(s.providerName().toLowerCase(Locale.ROOT), s);
    return new FallbackAiReviewClient(delegates, latch);
  }

  @Test
  void movesToTheNextProviderWhenThePrimaryFails() {
    ProviderLatch latch = latch();
    Stub claude = new Stub("CLAUDE", status(429, "Too Many Requests"), null);
    Stub ollama = new Stub("OLLAMA", null, result(7));

    assertEquals(7, chain(latch, claude, ollama).review(INPUT).confidence());
    assertEquals(1, claude.calls);
    assertEquals(1, ollama.calls);
  }

  @Test
  void opensTheLatchOnAnAvailabilityFailure() {
    ProviderLatch latch = latch();
    chain(latch, new Stub("CLAUDE", status(429, "Too Many Requests"), null),
                 new Stub("OLLAMA", null, result(5))).review(INPUT);

    assertTrue(latch.isOpen("review", "claude"));
  }

  @Test
  void skipsAProviderWhoseLatchIsOpenWithoutCallingIt() {
    ProviderLatch latch = latch();
    latch.recordFailure("review", "claude", FailureKind.AUTH, null);
    Stub claude = new Stub("CLAUDE", null, result(9));

    assertEquals(3,
        chain(latch, claude, new Stub("OLLAMA", null, result(3))).review(INPUT).confidence());
    assertEquals(0, claude.calls);
  }

  @Test
  void doesNotOpenTheLatchOnAnOutputFailure() {
    ProviderLatch latch = latch();

    assertThrows(RuntimeException.class,
        () -> chain(latch, new Stub("CLAUDE", new IllegalStateException("schema mismatch"), null),
                           new Stub("OLLAMA", new IllegalStateException("also bad"), null))
            .review(INPUT));

    assertFalse(latch.isOpen("review", "claude"));
    assertFalse(latch.isOpen("review", "ollama"));
  }

  @Test
  void propagatesTheLastFailureWhenEveryAttemptFailed() {
    ProviderLatch latch = latch();
    RuntimeException ollamaFailure = status(503, "Service Unavailable");

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> chain(latch, new Stub("CLAUDE", status(429, "Too Many Requests"), null),
                           new Stub("OLLAMA", ollamaFailure, null)).review(INPUT));
    assertSame(ollamaFailure, thrown);
  }

  @Test
  void closesTheLatchOnSuccess() {
    ProviderLatch latch = latch();
    latch.recordFailure("review", "ollama", FailureKind.TRANSIENT, null);
    latch.recordFailure("review", "ollama", FailureKind.TRANSIENT, null);

    chain(latch, new Stub("OLLAMA", null, result(4))).review(INPUT);

    assertFalse(latch.isOpen("review", "ollama"));
  }

  @Test
  void reportsTheProviderThatActuallyServed() {
    ProviderLatch latch = latch();
    FallbackAiReviewClient client = chain(latch,
        new Stub("CLAUDE", status(429, "Too Many Requests"), null),
        new Stub("OLLAMA", null, result(6)));

    client.review(INPUT);

    assertEquals("OLLAMA", client.providerName());
  }

  private FallbackAiReviewClient chain(
      ProviderLatch latch, Map<String, AiReviewClient> lastResort, Stub... stubs) {
    LinkedHashMap<String, AiReviewClient> delegates = new LinkedHashMap<>();
    for (Stub s : stubs) delegates.put(s.providerName().toLowerCase(Locale.ROOT), s);
    return new FallbackAiReviewClient(delegates, lastResort, latch);
  }

  @Test
  void usesTheFastClaudeWhileAUsableFallbackFollows() {
    ProviderLatch latch = latch();
    Stub fast = new Stub("CLAUDE", status(503, "Service Unavailable"), null);
    Stub patient = new Stub("CLAUDE", null, result(9));
    Stub ollama = new Stub("OLLAMA", null, result(4));

    assertEquals(4, chain(latch, Map.of("claude", patient), fast, ollama).review(INPUT).confidence());
    assertEquals(1, fast.calls);
    assertEquals(0, patient.calls);
  }

  @Test
  void usesTheLastResortClaudeWhenTheFallbackIsBlocked() {
    ProviderLatch latch = latch();
    latch.recordFailure("review", "ollama", FailureKind.AUTH, null);
    Stub fast = new Stub("CLAUDE", null, result(1));
    Stub patient = new Stub("CLAUDE", null, result(5));
    Stub ollama = new Stub("OLLAMA", null, result(2));

    assertEquals(5, chain(latch, Map.of("claude", patient), fast, ollama).review(INPUT).confidence());
    assertEquals(0, fast.calls);
    assertEquals(1, patient.calls);
    assertEquals(0, ollama.calls);
  }

  @Test
  void callsThePrimaryLastResortOnceWhenEveryProviderIsBlocked() {
    // 스펙 §3.1-1 규칙 ③: 폴백을 끈 상태처럼 1순위를 부른다(예전에는 LLM_ALL_PROVIDERS_BLOCKED 로 실패).
    ProviderLatch latch = latch();
    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);
    latch.recordFailure("review", "ollama", FailureKind.AUTH, null);
    Stub fast = new Stub("CLAUDE", null, result(1));
    Stub patient = new Stub("CLAUDE", null, result(8));
    Stub ollama = new Stub("OLLAMA", null, result(2));

    assertEquals(8, chain(latch, Map.of("claude", patient), fast, ollama).review(INPUT).confidence());
    assertEquals(1, patient.calls);
    assertEquals(0, fast.calls);
    assertEquals(0, ollama.calls);
  }

  @Test
  void surfacesTheLastResortFailureWhenEveryProviderIsBlocked() {
    ProviderLatch latch = latch();
    latch.recordFailure("review", "claude", FailureKind.RATE_LIMIT, null);
    latch.recordFailure("review", "ollama", FailureKind.AUTH, null);
    TransientReviewException failure = new TransientReviewException("LLM_RATELIMIT", "Claude 429", null);
    Stub patient = new Stub("CLAUDE", failure, null);

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> chain(latch, Map.of("claude", patient),
            new Stub("CLAUDE", null, result(1)), new Stub("OLLAMA", null, result(2))).review(INPUT));
    assertSame(failure, thrown);
  }

  @Test
  void fallsBackToTheFastDelegateWhenNoLastResortVariantIsGiven() {
    ProviderLatch latch = latch();
    latch.recordFailure("review", "ollama", FailureKind.AUTH, null);
    Stub claude = new Stub("CLAUDE", null, result(6));

    assertEquals(6, chain(latch, claude, new Stub("OLLAMA", null, result(2))).review(INPUT).confidence());
    assertEquals(1, claude.calls);
  }

  @Test
  void reportsClaudeWhenTheLastResortVariantServed() {
    // Review Focus 5: 저장·발행되는 이름은 구현체가 말하는 이름이다.
    ProviderLatch latch = latch();
    latch.recordFailure("review", "ollama", FailureKind.AUTH, null);
    FallbackAiReviewClient client = chain(latch,
        Map.of("claude", new Stub("CLAUDE", null, result(5))),
        new Stub("CLAUDE", null, result(1)), new Stub("OLLAMA", null, result(2)));

    client.review(INPUT);

    assertEquals("CLAUDE", client.providerName());
  }
}
