package ai.devpath.aigw.mentor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
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

  private final MentorService mentorService;
  private final AsyncTaskExecutor mentorExecutor;
  private final boolean enabled;
  private final long timeoutMs;

  public MentorController(MentorService mentorService,
      @org.springframework.beans.factory.annotation.Qualifier("mentorExecutor") AsyncTaskExecutor mentorExecutor,
      @Value("${devpath.mentor.enabled:true}") boolean enabled,
      @Value("${devpath.mentor.timeout:PT60S}") java.time.Duration timeout) {
    this.mentorService = mentorService;
    this.mentorExecutor = mentorExecutor;
    this.enabled = enabled;
    this.timeoutMs = timeout.toMillis();
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
    SseEmitter emitter = new SseEmitter(timeoutMs);
    emitter.onTimeout(emitter::complete);
    mentorExecutor.execute(() ->
        mentorService.streamAnswer(userId, req.message(), req.contentId(), emitter));
    return emitter;
  }
}
