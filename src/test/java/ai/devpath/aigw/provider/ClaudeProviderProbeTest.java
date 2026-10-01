package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 딥스텁 대신 실제 SDK 클라이언트를 MockWebServer 에 붙인다 — 탐색이 실제로 어떤 요청을 보내고
 * 실패가 어떤 <b>SDK 예외 타입</b>으로 올라오는지가 핵심이다(그 타입을 스케줄러가 분류한다).
 */
class ClaudeProviderProbeTest {

  private static final String MESSAGE_JSON = """
      {"id":"msg_probe","type":"message","role":"assistant","model":"claude-sonnet-4-6",
       "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
       "usage":{"input_tokens":1,"output_tokens":1}}
      """;

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

  private AnthropicClient sdk() {
    return AnthropicOkHttpClient.builder()
        .apiKey("sk-test")
        .baseUrl(server.url("/").toString())
        .timeout(Duration.ofSeconds(5))
        .maxRetries(0)
        .build();
  }

  private ClaudeProviderProbe probe(String feature) {
    return new ClaudeProviderProbe(feature, sdk(), "claude-sonnet-4-6");
  }

  @Test
  void reportsTheFeatureAndProviderItGuards() {
    ClaudeProviderProbe p = probe("review");

    assertEquals("review", p.feature());
    assertEquals("claude", p.provider());
  }

  @Test
  void sendsTheSmallestPossibleRequest() throws Exception {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json").setBody(MESSAGE_JSON));

    probe("review").ping();

    RecordedRequest request = server.takeRequest();
    assertEquals("POST", request.getMethod());
    assertTrue(request.getPath().endsWith("/v1/messages"), request.getPath());
    String body = request.getBody().readUtf8();
    // 출력 1토큰 — 크레딧이 소진된 상태라면 즉시 429 로 떨어져 비용이 아예 없다.
    assertTrue(body.contains("\"max_tokens\":1"), body);
    assertTrue(body.contains("claude-sonnet-4-6"), body);
  }

  @Test
  void letsARateLimitThroughAsTheSdkTypeTheSchedulerClassifies() {
    server.enqueue(new MockResponse().setResponseCode(429)
        .setHeader("Content-Type", "application/json")
        .setHeader("retry-after", "42")
        .setBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}"));

    RateLimitException thrown =
        assertThrows(RateLimitException.class, () -> probe("review").ping());

    // 스케줄러가 이 예외를 ProviderFailures 로 넘기면 기한이 Retry-After 로 잡힌다.
    assertEquals(FailureKind.RATE_LIMIT, ProviderFailures.classify(thrown).kind());
    assertEquals(Duration.ofSeconds(42), ProviderFailures.classify(thrown).retryAfter());
  }

  @Test
  void letsARevokedKeyThroughAsTheSdkTypeTheSchedulerClassifies() {
    server.enqueue(new MockResponse().setResponseCode(401)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"no\"}}"));

    UnauthorizedException thrown =
        assertThrows(UnauthorizedException.class, () -> probe("retention").ping());

    assertEquals(FailureKind.AUTH, ProviderFailures.classify(thrown).kind());
  }
}
