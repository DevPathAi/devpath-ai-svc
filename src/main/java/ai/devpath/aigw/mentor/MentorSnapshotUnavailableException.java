package ai.devpath.aigw.mentor;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** Snapshot 부재·owner/purpose/visibility 거부·wire 손상을 구분 없이 감추는 404. */
public class MentorSnapshotUnavailableException extends ApiException {

  public MentorSnapshotUnavailableException() {
    super(ErrorCode.RESOURCE_NOT_FOUND, "mentor snapshot unavailable");
  }
}
