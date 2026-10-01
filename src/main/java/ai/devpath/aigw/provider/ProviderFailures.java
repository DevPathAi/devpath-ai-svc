package ai.devpath.aigw.provider;

import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 예외를 {@link FailureKind} 로 분류한다. 스펙 §3 의 표가 정본이다.
 *
 * <p>Anthropic SDK 와 Ollama(Spring {@code RestClient})를 <b>같은 상태코드 규칙</b>으로 다룬다:
 * {@code AnthropicServiceException} 이 {@code statusCode()}·{@code headers()} 를 노출하는 추상
 * 기반이라(2026-10-01 실측) 타입별 분기가 필요 없고, Claude 의 429 도 {@code Retry-After} 를
 * 그대로 신뢰할 수 있다.
 *
 * <p>분류하지 못한 예외는 {@link FailureKind#OUTPUT_INVALID} 로 떨어진다 — <b>래치를 열지 않는</b>
 * 쪽이 안전한 기본값이다. 알 수 없는 예외로 provider 를 끊는 것이 더 나쁘다.
 */
public final class ProviderFailures {

  /** retryAfter 는 429 에 Retry-After(초)가 있을 때만 채워진다. */
  public record Classified(FailureKind kind, Duration retryAfter) {}

  private static final int MAX_CAUSE_DEPTH = 16;

  private ProviderFailures() {}

  public static Classified classify(Throwable t) {
    Throwable c = t;
    for (int depth = 0; c != null && depth < MAX_CAUSE_DEPTH; depth++) {
      Classified hit = classifyOne(c);
      if (hit != null) return hit;
      Throwable next = c.getCause();
      c = (next == c) ? null : next;   // 자기 참조 cause 로 무한 루프에 빠지지 않는다
    }
    return new Classified(FailureKind.OUTPUT_INVALID, null);
  }

  private static Classified classifyOne(Throwable t) {
    if (t instanceof RestClientResponseException http) {
      return fromStatus(http.getStatusCode().value(), retryAfter(http.getResponseHeaders()));
    }
    if (t instanceof AnthropicServiceException sdk) {
      return fromStatus(sdk.statusCode(), sdkRetryAfter(sdk));
    }
    if (t instanceof AnthropicException) {
      // 상태코드가 없는 SDK 실패(IO·재시도 가능·래핑). 보수적으로 가용성 문제로 본다.
      return new Classified(FailureKind.TRANSIENT, null);
    }
    if (t instanceof ResourceAccessException || t instanceof SocketTimeoutException) {
      return new Classified(FailureKind.TRANSIENT, null);
    }
    return null;
  }

  private static Classified fromStatus(int status, Duration retryAfter) {
    if (status == 401 || status == 403) return new Classified(FailureKind.AUTH, null);
    if (status == 429) return new Classified(FailureKind.RATE_LIMIT, retryAfter);
    if (status == 400) return new Classified(FailureKind.BAD_REQUEST, null);
    if (status >= 500) return new Classified(FailureKind.TRANSIENT, null);
    // 그 밖의 4xx(404 모델/경로 오류, 422 등)는 가용성 문제가 아니다 — 래치를 열지 않는다.
    return new Classified(FailureKind.OUTPUT_INVALID, null);
  }

  /** Retry-After 는 초(delta-seconds) 형태만 신뢰한다. HTTP-date 는 null 로 둔다. */
  private static Duration retryAfter(HttpHeaders headers) {
    return headers == null ? null : parseSeconds(headers.getFirst(HttpHeaders.RETRY_AFTER));
  }

  private static Duration sdkRetryAfter(AnthropicServiceException sdk) {
    try {
      List<String> values = sdk.headers().values("retry-after");
      return values.isEmpty() ? null : parseSeconds(values.get(0));
    } catch (RuntimeException e) {
      return null;   // 헤더를 읽지 못하는 것이 분류를 막아선 안 된다
    }
  }

  private static Duration parseSeconds(String raw) {
    if (raw == null) return null;
    try {
      long seconds = Long.parseLong(raw.trim());
      return seconds > 0 ? Duration.ofSeconds(seconds) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
