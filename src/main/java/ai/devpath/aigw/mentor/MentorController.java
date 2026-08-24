package ai.devpath.aigw.mentor;

import ai.devpath.aigw.release.ReleaseJourneyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 멘토 SSE(M-1/M-2). kill-switch 선체크(개시 전 비-200) → 전용 스레드에서 스트림. */
@RestController
@RequestMapping("/ai-mentor")
public class MentorController {

  private final MentorExecutionCoordinator coordinator;
  private final MentorSnapshotClient snapshotClient;
  private final boolean enabled;
  private final ReleaseJourneyRegistry release;

  @Autowired
  public MentorController(MentorExecutionCoordinator coordinator,
      MentorSnapshotClient snapshotClient,
      @Value("${devpath.mentor.enabled:true}") boolean enabled,
      ReleaseJourneyRegistry release) {
    this.coordinator = coordinator;
    this.snapshotClient = snapshotClient;
    this.enabled = enabled;
    this.release = release;
  }

  MentorController(MentorExecutionCoordinator coordinator,
      MentorSnapshotClient snapshotClient, boolean enabled) {
    this(coordinator, snapshotClient, enabled, null);
  }

  public SseEmitter sessions(@AuthenticationPrincipal Jwt jwt, @RequestBody MentorRequest req) {
    return sessions(jwt, req, null, null);
  }

  @PostMapping("/sessions")
  public SseEmitter sessions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody MentorRequest req,
      @RequestHeader(name = "X-Candidate-Spec-Sha256", required = false) String candidate,
      @RequestHeader(name = "X-Release-Run-Key", required = false) String runKey) {
    if (!enabled) {
      throw new MentorKillSwitchException("AI mentor is disabled"); // 개시 전 503(M-2)
    }
    if (req == null || req.message() == null || req.message().isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    long userId = Long.parseLong(jwt.getSubject());
    MentorSnapshotContext approvedContext = req.contextSnapshotId() == null
        ? null
        : snapshotClient.consume(req.contextSnapshotId(), jwt.getTokenValue());
    if (release != null) {
      var attempt = release.mentorAttempt(
          candidate, runKey, userId, approvedContext != null);
      if (attempt.tracked()) {
        return coordinator.start(
            userId, req.message(), req.contentId(), approvedContext, attempt);
      }
    }
    return coordinator.start(userId, req.message(), req.contentId(), approvedContext);
  }
}
