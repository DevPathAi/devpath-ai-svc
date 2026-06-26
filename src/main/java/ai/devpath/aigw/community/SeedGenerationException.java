package ai.devpath.aigw.community;

/** 시드 답변 생성 실패. errorCode는 seed.ready FAILED 이벤트에 전달(LLM_FAILED 등). */
public class SeedGenerationException extends RuntimeException {
  private final String errorCode;

  public SeedGenerationException(String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public String errorCode() { return errorCode; }
}
