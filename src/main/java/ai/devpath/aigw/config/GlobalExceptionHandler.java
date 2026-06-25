package ai.devpath.aigw.config;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
  }

  /** AI 멘토 kill-switch(개시 전 503, M-2). 프론트 ApiErrorCode가 AI_KILL_SWITCH_ACTIVE를 isKillSwitch로 매핑. */
  @ExceptionHandler(ai.devpath.aigw.mentor.MentorKillSwitchException.class)
  public ResponseEntity<Map<String, String>> killSwitch(ai.devpath.aigw.mentor.MentorKillSwitchException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("errorCode", "AI_KILL_SWITCH_ACTIVE", "message", e.getMessage()));
  }
}
