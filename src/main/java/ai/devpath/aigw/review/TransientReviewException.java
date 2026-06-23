package ai.devpath.aigw.review;

/** 재시도 가능한 일시 리뷰 오류(LLM 타임아웃/5xx/429). 리스너 밖으로 전파되어 Kafka가 재시도. */
public class TransientReviewException extends RuntimeException {
  private final String errorCode;

  public TransientReviewException(String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
