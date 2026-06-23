package ai.devpath.aigw.review;

/** 재시도 무의미한 영구 리뷰 오류(LLM 응답 파싱 실패 등). 즉시 finishFailed로 종료. */
public class PermanentReviewException extends RuntimeException {
  private final String errorCode;

  public PermanentReviewException(String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
