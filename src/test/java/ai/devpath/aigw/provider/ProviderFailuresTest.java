package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class ProviderFailuresTest {

  private static RestClientResponseException http(HttpStatus status, String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    if (retryAfter != null) headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
    return new RestClientResponseException(
        "boom", status.value(), status.getReasonPhrase(), headers, null, null);
  }

  private static Headers sdkHeaders(String retryAfter) {
    Headers.Builder b = Headers.builder();
    if (retryAfter != null) b.put("retry-after", retryAfter);
    return b.build();
  }

  @Test
  void mapsOllamaHttpStatusesToTheSpecTable() {
    assertEquals(FailureKind.AUTH,
        ProviderFailures.classify(http(HttpStatus.UNAUTHORIZED, null)).kind());
    assertEquals(FailureKind.AUTH,
        ProviderFailures.classify(http(HttpStatus.FORBIDDEN, null)).kind());
    assertEquals(FailureKind.RATE_LIMIT,
        ProviderFailures.classify(http(HttpStatus.TOO_MANY_REQUESTS, null)).kind());
    assertEquals(FailureKind.TRANSIENT,
        ProviderFailures.classify(http(HttpStatus.BAD_GATEWAY, null)).kind());
    assertEquals(FailureKind.BAD_REQUEST,
        ProviderFailures.classify(http(HttpStatus.BAD_REQUEST, null)).kind());
  }

  @Test
  void readsRetryAfterSecondsFromAnOllamaResponse() {
    assertEquals(Duration.ofSeconds(90),
        ProviderFailures.classify(http(HttpStatus.TOO_MANY_REQUESTS, "90")).retryAfter());
  }

  @Test
  void leavesRetryAfterNullWhenTheHeaderIsAbsentOrUnparseable() {
    assertNull(ProviderFailures.classify(http(HttpStatus.TOO_MANY_REQUESTS, null)).retryAfter());
    assertNull(ProviderFailures.classify(
        http(HttpStatus.TOO_MANY_REQUESTS, "Wed, 01 Oct 2026 00:00:00 GMT")).retryAfter());
    assertNull(ProviderFailures.classify(http(HttpStatus.TOO_MANY_REQUESTS, "0")).retryAfter());
  }

  @Test
  void mapsConnectionAndTimeoutFailuresToTransient() {
    assertEquals(FailureKind.TRANSIENT,
        ProviderFailures.classify(new ResourceAccessException("timeout")).kind());
    assertEquals(FailureKind.TRANSIENT,
        ProviderFailures.classify(new SocketTimeoutException("read timed out")).kind());
  }

  @Test
  void mapsTheAnthropicSdkByItsStatusCode() {
    // AnthropicServiceException 은 statusCode() 를 노출하는 추상 기반이다 — 타입별 분기가 필요 없다.
    assertEquals(FailureKind.AUTH, ProviderFailures.classify(
        UnauthorizedException.builder().headers(sdkHeaders(null)).body(JsonValue.from(null))
            .build()).kind());
    assertEquals(FailureKind.AUTH, ProviderFailures.classify(
        PermissionDeniedException.builder().headers(sdkHeaders(null)).body(JsonValue.from(null))
            .build()).kind());
    assertEquals(FailureKind.BAD_REQUEST, ProviderFailures.classify(
        BadRequestException.builder().headers(sdkHeaders(null)).body(JsonValue.from(null))
            .build()).kind());
    assertEquals(FailureKind.RATE_LIMIT, ProviderFailures.classify(
        RateLimitException.builder().headers(sdkHeaders(null)).body(JsonValue.from(null))
            .build()).kind());
    assertEquals(FailureKind.TRANSIENT, ProviderFailures.classify(
        InternalServerException.builder().statusCode(503).headers(sdkHeaders(null))
            .body(JsonValue.from(null)).build()).kind());
  }

  @Test
  void readsRetryAfterFromTheAnthropicSdkHeaders() {
    // 실측(2026-10-01): AnthropicServiceException.headers() 가 있으므로 Claude 의 429 도
    // Retry-After 를 그대로 신뢰할 수 있다.
    assertEquals(Duration.ofSeconds(120), ProviderFailures.classify(
        RateLimitException.builder().headers(sdkHeaders("120")).body(JsonValue.from(null))
            .build()).retryAfter());
  }

  @Test
  void treatsAnUnmappedSdkStatusAsOutputInvalidSoItNeverOpensTheLatch() {
    // 404 는 가용성 문제가 아니다 — 경로나 모델 이름이 틀린 것이다.
    assertEquals(FailureKind.OUTPUT_INVALID, ProviderFailures.classify(
        NotFoundException.builder().headers(sdkHeaders(null)).body(JsonValue.from(null))
            .build()).kind());
  }

  @Test
  void mapsTheSdkIoFailureToTransient() {
    assertEquals(FailureKind.TRANSIENT,
        ProviderFailures.classify(new AnthropicIoException("io", new IOException())).kind());
  }

  @Test
  void treatsAStatuslessAnthropicFailureAsTransientRatherThanIgnoringIt() {
    assertEquals(FailureKind.TRANSIENT,
        ProviderFailures.classify(new AnthropicException("unknown")).kind());
  }

  @Test
  void treatsAnUnrecognisedThrowableAsOutputInvalidSoItNeverOpensTheLatch() {
    assertEquals(FailureKind.OUTPUT_INVALID,
        ProviderFailures.classify(new IllegalStateException("schema mismatch")).kind());
  }

  @Test
  void unwrapsACauseChain() {
    assertEquals(FailureKind.RATE_LIMIT,
        ProviderFailures.classify(
            new RuntimeException("wrapped", http(HttpStatus.TOO_MANY_REQUESTS, null))).kind());
  }

  @Test
  void survivesACyclicCauseChain() {
    // Java 는 자기 참조(initCause(this))를 금지하지만 a -> b -> a 순환은 만들 수 있다.
    // 깊이 상한이 없으면 여기서 무한 루프에 빠진다.
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);

    assertEquals(FailureKind.OUTPUT_INVALID, ProviderFailures.classify(a).kind());
  }

  @Test
  void findsAMappableCauseBehindACycle() {
    RuntimeException a = new RuntimeException("a", http(HttpStatus.TOO_MANY_REQUESTS, "30"));
    RuntimeException outer = new RuntimeException("outer", a);

    assertEquals(FailureKind.RATE_LIMIT, ProviderFailures.classify(outer).kind());
    assertEquals(Duration.ofSeconds(30), ProviderFailures.classify(outer).retryAfter());
  }
}
