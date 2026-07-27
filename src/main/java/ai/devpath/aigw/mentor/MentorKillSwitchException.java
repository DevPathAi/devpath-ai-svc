package ai.devpath.aigw.mentor;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** AI 멘토 kill-switch(개시 전 503, M-2) → 스펙 §3.4 AI_KILL_SWITCH_ACTIVE. 공용 ApiExceptionHandler가 envelope로 렌더. */
public class MentorKillSwitchException extends ApiException {
  public MentorKillSwitchException(String message) {
    super(ErrorCode.AI_KILL_SWITCH_ACTIVE, message);
  }
}
