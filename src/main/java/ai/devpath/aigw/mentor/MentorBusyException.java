package ai.devpath.aigw.mentor;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** Transient Mentor executor saturation. This is distinct from a learner quota. */
public final class MentorBusyException extends ApiException {

  private static final long serialVersionUID = 1L;

  public MentorBusyException() {
    super(ErrorCode.MENTOR_BUSY, "mentor is busy; retry later");
  }
}
