package ai.devpath.aigw.mentor;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Strictly nested provider, request, and SSE deadlines for one Mentor request. */
@Component
public final class MentorTimeoutPolicy {

  private final Duration providerTimeout;
  private final Duration requestTimeout;
  private final Duration sseTimeout;

  public MentorTimeoutPolicy(
      @Value("${devpath.mentor.provider-timeout:PT50S}") Duration providerTimeout,
      @Value("${devpath.mentor.request-timeout:PT55S}") Duration requestTimeout,
      @Value("${devpath.mentor.sse-timeout:PT60S}") Duration sseTimeout) {
    requirePositive("provider timeout", providerTimeout);
    requirePositive("request timeout", requestTimeout);
    requirePositive("SSE timeout", sseTimeout);
    if (providerTimeout.compareTo(requestTimeout) >= 0) {
      throw new IllegalArgumentException("provider timeout must be shorter than request timeout");
    }
    if (requestTimeout.compareTo(sseTimeout) >= 0) {
      throw new IllegalArgumentException("request timeout must be shorter than SSE timeout");
    }
    this.providerTimeout = providerTimeout;
    this.requestTimeout = requestTimeout;
    this.sseTimeout = sseTimeout;
  }

  public Duration providerTimeout() {
    return providerTimeout;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public Duration sseTimeout() {
    return sseTimeout;
  }

  private static void requirePositive(String name, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
