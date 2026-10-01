package ai.devpath.aigw.community;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.ollama.OllamaClient;
import ai.devpath.aigw.provider.FailureKind;
import ai.devpath.aigw.provider.ProviderLatch;
import ai.devpath.shared.event.CommunityQuestionPostedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/**
 * 스펙 §9 통합 — <b>서비스</b>를 실제 {@link FallbackAiSeedClient} 로 관통시킨다.
 *
 * <p>시드는 성공 경로({@code publishDone})와 <b>실패 경로</b>({@code publishFailed}) 양쪽에서
 * {@code providerName()} 을 발행한다. 실패 경로가 특히 위험했다 — ThreadLocal 을 비우지 않으면
 * 그 워커 스레드의 <b>직전 요청</b> provider 가 이번 실패의 원인으로 기록된다.
 */
class CommunitySeedServiceProviderRecordingTest {

  private static final class Stub implements AiSeedClient {
    private final String name;
    private final RuntimeException failure;
    private final SeedAnswer success;

    Stub(String name, RuntimeException failure, SeedAnswer success) {
      this.name = name;
      this.failure = failure;
      this.success = success;
    }

    @Override public SeedAnswer generate(SeedInput input) {
      if (failure != null) throw failure;
      return success;
    }

    @Override public String providerName() { return name; }
  }

  private static RestClientResponseException status(int code, String reason) {
    return new RestClientResponseException(reason, code, reason, new HttpHeaders(), null, null);
  }

  private static ProviderLatch latch() {
    return new ProviderLatch(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
  }

  private static CommunityQuestionPostedEvent event(long questionId) {
    return new CommunityQuestionPostedEvent(UUID.randomUUID(), Instant.now(), 42L,
        questionId, questionId, "비동기란?", "Future가 헷갈립니다.");
  }

  private final OllamaClient ollama = mock(OllamaClient.class);
  private final CommunitySeedEventPublisher publisher = mock(CommunitySeedEventPublisher.class);

  @Test
  void publishesTheProviderThatActuallyServedAfterAFallback() {
    LinkedHashMap<String, AiSeedClient> chain = new LinkedHashMap<>();
    chain.put("claude", new Stub("CLAUDE", status(429, "Too Many Requests"), null));
    chain.put("ollama", new Stub("OLLAMA", null, new SeedAnswer("방향 제시 초안")));
    when(ollama.embed(anyList())).thenThrow(new IllegalStateException("embed down"));

    new CommunitySeedService(
        new FallbackAiSeedClient(chain, latch()), ollama, publisher, true).process(event(7L));

    verify(publisher).publishDone(eq(7L), eq("방향 제시 초안"), eq("OLLAMA"), isNull());
  }

  @Test
  void doesNotBlameThePreviousRequestsProviderOnTheFailurePath() {
    // 같은 스레드에서 1) ollama 가 응답한 요청 → 2) 모든 provider 가 차단된 요청.
    // ThreadLocal 을 비우지 않으면 2) 의 FAILED 가 "OLLAMA" 탓으로 기록된다.
    ProviderLatch latch = latch();
    LinkedHashMap<String, AiSeedClient> chain = new LinkedHashMap<>();
    chain.put("claude", new Stub("CLAUDE", status(429, "Too Many Requests"), null));
    chain.put("ollama", new Stub("OLLAMA", null, new SeedAnswer("초안")));
    when(ollama.embed(anyList())).thenThrow(new IllegalStateException("embed down"));

    AiSeedClient client = new FallbackAiSeedClient(chain, latch);
    CommunitySeedService service = new CommunitySeedService(client, ollama, publisher, true);

    service.process(event(7L));
    verify(publisher).publishDone(eq(7L), any(), eq("OLLAMA"), isNull());

    latch.recordFailure("community-seed", "ollama", FailureKind.AUTH, null);
    service.process(event(8L));

    verify(publisher).publishFailed(
        eq(8L), eq("LLM_ALL_PROVIDERS_BLOCKED"), eq("CLAUDE"), isNull());
  }
}
