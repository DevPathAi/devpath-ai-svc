package ai.devpath.aigw.mentor;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** LCS transport/server 장애를 raw 응답 없이 나타내는 재시도 가능 503. */
public class MentorSnapshotServiceUnavailableException extends ApiException {

  public MentorSnapshotServiceUnavailableException() {
    super(ErrorCode.STORAGE_UNAVAILABLE, "mentor snapshot temporarily unavailable");
  }
}
