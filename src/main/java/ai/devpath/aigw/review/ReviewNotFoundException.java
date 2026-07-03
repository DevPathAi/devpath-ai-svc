package ai.devpath.aigw.review;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** 리뷰 없음 → 스펙 §3.4 RESOURCE_NOT_FOUND(404). 공용 ApiExceptionHandler가 envelope로 렌더. */
public class ReviewNotFoundException extends ApiException {
  public ReviewNotFoundException(String message) {
    super(ErrorCode.RESOURCE_NOT_FOUND, message);
  }
}
