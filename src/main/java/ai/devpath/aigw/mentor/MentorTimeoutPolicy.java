package ai.devpath.aigw.mentor;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Strictly nested provider, request, and SSE deadlines for one Mentor request. */
@Component
public final class MentorTimeoutPolicy {

  private final Duration providerTimeout;
  private final Duration requestTimeout;
  private final Duration sseTimeout;
  private final Duration heartbeatInterval;

  @Autowired
  public MentorTimeoutPolicy(
      @Value("${devpath.mentor.provider-timeout:PT50S}") Duration providerTimeout,
      @Value("${devpath.mentor.request-timeout:PT55S}") Duration requestTimeout,
      @Value("${devpath.mentor.sse-timeout:PT60S}") Duration sseTimeout,
      @Value("${devpath.mentor.heartbeat-interval:PT15S}") Duration heartbeatInterval) {
    requirePositive("provider timeout", providerTimeout);
    requirePositive("request timeout", requestTimeout);
    requirePositive("SSE timeout", sseTimeout);
    requirePositive("heartbeat interval", heartbeatInterval);
    if (heartbeatInterval.compareTo(providerTimeout) >= 0) {
      throw new IllegalArgumentException("heartbeat interval must be shorter than provider timeout");
    }
    if (providerTimeout.compareTo(requestTimeout) >= 0) {
      throw new IllegalArgumentException("provider timeout must be shorter than request timeout");
    }
    if (requestTimeout.compareTo(sseTimeout) >= 0) {
      throw new IllegalArgumentException("request timeout must be shorter than SSE timeout");
    }
    this.providerTimeout = providerTimeout;
    this.requestTimeout = requestTimeout;
    this.sseTimeout = sseTimeout;
    this.heartbeatInterval = heartbeatInterval;
  }

  MentorTimeoutPolicy(Duration providerTimeout, Duration requestTimeout, Duration sseTimeout) {
    this(providerTimeout, requestTimeout, sseTimeout, providerTimeout.dividedBy(2));
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

  public Duration heartbeatInterval() {
    return heartbeatInterval;
  }

  private static void requirePositive(String name, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
