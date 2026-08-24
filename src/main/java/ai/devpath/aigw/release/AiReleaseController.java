package ai.devpath.aigw.release;

import ai.devpath.aigw.review.ReviewPersistenceService;
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
    if (!(rawUserId instanceof Number number) || number.longValue() <= 0) {
      throw new IllegalArgumentException("release fixture user id is required");
    }
    long userId = number.longValue();
    if ("clear-faults".equals(command)) {
      var replay = release.clear(candidate, runKey, userId);
      if (replay.isPresent()) {
        if (!reviewPersistence.reopenReleaseFailure(replay.get())) {
          throw new IllegalStateException("release review recovery boundary did not match");
        }
        release.recordReviewReleased(replay.get());
      }
    } else {
      release.arm(candidate, runKey, userId, command);
    }
    return Map.of("accepted", true);
  }

  @GetMapping("/checkpoints/{checkpoint}")
  public Map<String, Object> checkpoint(
      @PathVariable String candidate,
      @PathVariable String runKey,
      @PathVariable String checkpoint) {
    return Map.of("passed", release.checkpoint(candidate, runKey, checkpoint));
  }
}
