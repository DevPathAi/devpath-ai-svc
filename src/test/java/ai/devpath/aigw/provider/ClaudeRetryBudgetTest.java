package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.anthropic.client.AnthropicClient;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SDK 재시도 예산은 <b>체인을 따라간다</b>(리뷰 I4 결정).
 *
 * <p>보정 §D-② 가 재시도를 끈 이유는 「SDK 가 429 를 삼키면 래치의 rate_limit 판정이 왜곡된다」인데,
 * 그 왜곡은 <b>체인이 있을 때만</b> 생긴다. 보정 §C 가 {@code *_FALLBACK} 을 빈 값으로 출하하므로
 * 지금 운영의 체인은 전부 길이 1 이고 래치도 래퍼도 끼지 않는다 — 그 상태에서 재시도를 끄는 것은
 * 순손실이다. 특히 community-seed 는 실패를 잡아 {@code publishFailed} 로 끝내서 Kafka 재시도가
 * 없고(=한 번의 503 이 시드 답변을 영구히 잃는다), retention 은 동기 HTTP 라 재시도가 아예 없다.
 *
 * <p>그래서 체인 길이가 2 이상일 때만 0 으로 내린다. 값은 소스 텍스트가 아니라 <b>실제 요청 횟수</b>로
 * 못박는다 — 주석 안의 문자열도 통과시키던 기존 검사(M2)를 대체한다.
 */
class ClaudeRetryBudgetTest {

  private MockWebServer server;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stop() throws Exception {
    server.shutdown();
  }

  private AnthropicClient client(String provider, String fallbackCsv) {
    return ClaudeClients.build(
        "sk-test", server.url("/").toString(), Duration.ofSeconds(5), provider, fallbackCsv);
  }

  private void ping(AnthropicClient client) {
    new ClaudeProviderProbe("review", client, "claude-sonnet-4-6").ping();
  }

  @Test
  void keepsTheSdkRetryBudgetWhileNoFallbackIsConfigured() {
    for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(500));

    assertThrows(RuntimeException.class, () -> ping(client("claude", "")));

    // SDK 기본 maxRetries = 2(javap 실측, anthropic-java-core 2.34.0 ClientOptions$Builder) → 총 3회.
    assertEquals(3, server.getRequestCount());
  }

  @Test
  void disablesSdkRetriesOnceAFallbackChainIsConfigured() {
    server.enqueue(new MockResponse().setResponseCode(500));

    assertThrows(RuntimeException.class, () -> ping(client("claude", "ollama")));

    // 체인이 있으면 첫 실패가 즉시 올라와야 래치가 제때 열리고 폴백이 그 자리를 받는다.
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void aFallbackThatRepeatsThePrimaryIsNotAChain() {
    // ProviderChain 은 중복을 제거한다 — claude,claude 는 길이 1 이라 래퍼가 끼지 않는다.
    for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(500));

    assertThrows(RuntimeException.class, () -> ping(client("claude", " claude , ")));

    assertEquals(3, server.getRequestCount());
  }

  @Test
  void theLastResortCopyRestoresTheSdkRetryBudgetWithoutTouchingTheFastClient() {
    for (int i = 0; i < 4; i++) server.enqueue(new MockResponse().setResponseCode(500));
    AnthropicClient fast = client("claude", "ollama");

    assertThrows(RuntimeException.class, () -> ping(ClaudeClients.lastResort(fast)));
    // SDK 기본 maxRetries = 2 → 총 3회.
    assertEquals(3, server.getRequestCount());

    assertThrows(RuntimeException.class, () -> ping(fast));
    // 원본(체인용, 재시도 0)은 그대로다 — 1회만 더.
    assertEquals(4, server.getRequestCount());
  }
}
