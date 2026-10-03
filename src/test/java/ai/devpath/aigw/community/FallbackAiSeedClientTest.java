package ai.devpath.aigw.community;

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
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class FallbackAiSeedClientTest {

  private static final SeedInput INPUT = null;

  private static final class Stub implements AiSeedClient {
    private final String name;
    private final RuntimeException failure;
    private final String answer;
    int calls;

    Stub(String name, RuntimeException failure, String answer) {
      this.name = name;
      this.failure = failure;
      this.answer = answer;
    }

    @Override public SeedAnswer generate(SeedInput input) {
      calls++;
      if (failure != null) throw failure;
      return new SeedAnswer(answer);
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

  private FallbackAiSeedClient chain(ProviderLatch latch, Stub... stubs) {
    LinkedHashMap<String, AiSeedClient> delegates = new LinkedHashMap<>();
    for (Stub s : stubs) delegates.put(s.providerName().toLowerCase(Locale.ROOT), s);
    return new FallbackAiSeedClient(delegates, latch);
  }

  @Test
  void movesToTheNextProviderWhenThePrimaryFails() {
    ProviderLatch latch = latch();
    Stub claude = new Stub("CLAUDE", status(401, "Unauthorized"), null);

    assertEquals("from ollama",
        chain(latch, claude, new Stub("OLLAMA", null, "from ollama")).generate(INPUT).content());
    assertEquals(1, claude.calls);
  }

  @Test
  void opensTheLatchOnAnAuthFailure() {
    ProviderLatch latch = latch();
    chain(latch, new Stub("CLAUDE", status(401, "Unauthorized"), null),
                 new Stub("OLLAMA", null, "ok")).generate(INPUT);

    assertTrue(latch.isOpen("community-seed", "claude"));
  }

  @Test
  void skipsAProviderWhoseLatchIsOpenWithoutCallingIt() {
    ProviderLatch latch = latch();
    latch.recordFailure("community-seed", "claude", FailureKind.AUTH, null);
    Stub claude = new Stub("CLAUDE", null, "never");

    assertEquals("from ollama",
        chain(latch, claude, new Stub("OLLAMA", null, "from ollama")).generate(INPUT).content());
    assertEquals(0, claude.calls);
  }

  @Test
  void doesNotOpenTheLatchOnAnOutputFailure() {
    ProviderLatch latch = latch();

    assertThrows(RuntimeException.class,
        () -> chain(latch, new Stub("CLAUDE", new IllegalStateException("blank"), null))
            .generate(INPUT));

    assertFalse(latch.isOpen("community-seed", "claude"));
  }

  @Test
  void reportsTheProviderThatActuallyServed() {
    ProviderLatch latch = latch();
    FallbackAiSeedClient client = chain(latch,
        new Stub("CLAUDE", status(401, "Unauthorized"), null), new Stub("OLLAMA", null, "ok"));

    client.generate(INPUT);

    assertEquals("OLLAMA", client.providerName());
  }

  private FallbackAiSeedClient chain(
      ProviderLatch latch, Map<String, AiSeedClient> lastResort, Stub... stubs) {
    LinkedHashMap<String, AiSeedClient> delegates = new LinkedHashMap<>();
    for (Stub s : stubs) delegates.put(s.providerName().toLowerCase(Locale.ROOT), s);
    return new FallbackAiSeedClient(delegates, lastResort, latch);
  }

  @Test
  void usesTheFastClaudeWhileAUsableFallbackFollows() {
    ProviderLatch latch = latch();
    Stub fast = new Stub("CLAUDE", status(503, "Service Unavailable"), null);
    Stub patient = new Stub("CLAUDE", null, "patient");
    Stub ollama = new Stub("OLLAMA", null, "ollama");

    assertEquals("ollama",
        chain(latch, Map.of("claude", patient), fast, ollama).generate(INPUT).content());
    assertEquals(1, fast.calls);
    assertEquals(0, patient.calls);
  }

  @Test
  void usesTheLastResortClaudeWhenTheFallbackIsBlocked() {
    ProviderLatch latch = latch();
    latch.recordFailure("community-seed", "ollama", FailureKind.AUTH, null);
    Stub fast = new Stub("CLAUDE", null, "fast");
    Stub patient = new Stub("CLAUDE", null, "patient");

    assertEquals("patient", chain(latch, Map.of("claude", patient), fast,
        new Stub("OLLAMA", null, "ollama")).generate(INPUT).content());
    assertEquals(0, fast.calls);
    assertEquals(1, patient.calls);
  }

  @Test
  void callsThePrimaryLastResortOnceWhenEveryProviderIsBlocked() {
    // 스펙 §3.1-1 규칙 ③(예전에는 LLM_ALL_PROVIDERS_BLOCKED 로 실패).
    ProviderLatch latch = latch();
    latch.recordFailure("community-seed", "claude", FailureKind.RATE_LIMIT, null);
    latch.recordFailure("community-seed", "ollama", FailureKind.AUTH, null);
    Stub patient = new Stub("CLAUDE", null, "patient");

    assertEquals("patient", chain(latch, Map.of("claude", patient),
        new Stub("CLAUDE", null, "fast"), new Stub("OLLAMA", null, "ollama"))
        .generate(INPUT).content());
    assertEquals(1, patient.calls);
  }

  @Test
  void surfacesTheLastResortFailureWhenEveryProviderIsBlocked() {
    ProviderLatch latch = latch();
    latch.recordFailure("community-seed", "claude", FailureKind.RATE_LIMIT, null);
    latch.recordFailure("community-seed", "ollama", FailureKind.AUTH, null);
    SeedGenerationException failure =
        new SeedGenerationException("LLM_FAILED", "Claude seed 호출 실패", null);

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> chain(latch, Map.of("claude", new Stub("CLAUDE", failure, null)),
            new Stub("CLAUDE", null, "fast"), new Stub("OLLAMA", null, "ollama")).generate(INPUT));
    assertSame(failure, thrown);
  }

  @Test
  void reportsClaudeWhenTheLastResortVariantServed() {
    ProviderLatch latch = latch();
    latch.recordFailure("community-seed", "ollama", FailureKind.AUTH, null);
    FallbackAiSeedClient client = chain(latch,
        Map.of("claude", new Stub("CLAUDE", null, "patient")),
        new Stub("CLAUDE", null, "fast"), new Stub("OLLAMA", null, "ollama"));

    client.generate(INPUT);

    assertEquals("CLAUDE", client.providerName());
  }
}
