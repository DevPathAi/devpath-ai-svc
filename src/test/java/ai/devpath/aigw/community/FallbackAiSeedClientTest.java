package ai.devpath.aigw.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.aigw.provider.FailureKind;
import ai.devpath.aigw.provider.ProviderLatch;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
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
  void throwsTheExistingSeedExceptionWhenEveryProviderIsBlocked() {
    ProviderLatch latch = latch();
    latch.recordFailure("community-seed", "claude", FailureKind.AUTH, null);
    latch.recordFailure("community-seed", "ollama", FailureKind.AUTH, null);

    SeedGenerationException thrown = assertThrows(SeedGenerationException.class,
        () -> chain(latch, new Stub("CLAUDE", null, "x"), new Stub("OLLAMA", null, "y"))
            .generate(INPUT));
    assertEquals("LLM_ALL_PROVIDERS_BLOCKED", thrown.errorCode());
  }

  @Test
  void reportsTheProviderThatActuallyServed() {
    ProviderLatch latch = latch();
    FallbackAiSeedClient client = chain(latch,
        new Stub("CLAUDE", status(401, "Unauthorized"), null), new Stub("OLLAMA", null, "ok"));

    client.generate(INPUT);

    assertEquals("OLLAMA", client.providerName());
  }
}
