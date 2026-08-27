package ai.devpath.aigw.release;

import ai.devpath.aigw.review.ReviewPersistenceService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workload-authenticated staging control; neither credentials nor raw user payloads are returned. */
@RestController
@RequestMapping("/internal/release/ai/{candidate}/{runKey}")
@ConditionalOnProperty(name = "devpath.release.enabled", havingValue = "true")
public class AiReleaseController {
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  private final ReleaseJourneyRegistry release;
  private final ReviewPersistenceService reviewPersistence;

  public AiReleaseController(
      ReleaseJourneyRegistry release,
      ReviewPersistenceService reviewPersistence) {
    this.release = release;
    this.reviewPersistence = reviewPersistence;
  }

  @PostMapping("/commands/{command}")
  public Map<String, Object> command(
      @PathVariable String candidate,
      @PathVariable String runKey,
      @PathVariable String command,
      @RequestBody(required = false) Map<String, Object> body) {
    Object rawUserId = body == null ? null : body.get("user_id");
    long userId = requirePositiveSafeInteger(
        rawUserId, "release fixture user id is required");
    boolean reviewFault = "fail-next-review".equals(command);
    Long priorSandboxSessionId = null;
    if (reviewFault) {
      Object rawPriorSessionId = body.get("prior_sandbox_session_id");
      priorSandboxSessionId = requirePositiveSafeInteger(
          rawPriorSessionId, "prior sandbox session id is required");
    }
    if (body.size() != (reviewFault ? 2 : 1)
        || !body.containsKey("user_id")
        || reviewFault != body.containsKey("prior_sandbox_session_id")) {
      throw new IllegalArgumentException("AI release command payload is invalid");
    }
    if ("clear-faults".equals(command)) {
      var replay = release.clear(candidate, runKey, userId);
      if (replay.isPresent()) {
        if (!reviewPersistence.reopenReleaseFailure(replay.get())) {
          throw new IllegalStateException("release review recovery boundary did not match");
        }
        release.recordReviewReleased(replay.get());
      }
    } else {
      release.arm(candidate, runKey, userId, command, priorSandboxSessionId);
    }
    return Map.of("accepted", true);
  }

  private static long requirePositiveSafeInteger(Object rawValue, String message) {
    try {
      if (!(rawValue instanceof Number number)) throw new ArithmeticException();
      long value = new BigDecimal(number.toString()).longValueExact();
      if (value <= 0 || value > MAX_SAFE_INTEGER) throw new ArithmeticException();
      return value;
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new IllegalArgumentException(message);
    }
  }

  @GetMapping("/checkpoints/{checkpoint}")
  public Map<String, Object> checkpoint(
      @PathVariable String candidate,
      @PathVariable String runKey,
      @PathVariable String checkpoint) {
    return Map.of("passed", release.checkpoint(candidate, runKey, checkpoint));
  }
}
