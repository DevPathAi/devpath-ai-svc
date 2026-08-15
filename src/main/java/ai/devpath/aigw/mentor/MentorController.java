package ai.devpath.aigw.mentor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  public MentorController(MentorExecutionCoordinator coordinator,
      MentorSnapshotClient snapshotClient,
      @Value("${devpath.mentor.enabled:true}") boolean enabled) {
    this.coordinator = coordinator;
    this.snapshotClient = snapshotClient;
    this.enabled = enabled;
  }

  @PostMapping("/sessions")
  public SseEmitter sessions(@AuthenticationPrincipal Jwt jwt, @RequestBody MentorRequest req) {
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
    return coordinator.start(userId, req.message(), req.contentId(), approvedContext);
  }
}
