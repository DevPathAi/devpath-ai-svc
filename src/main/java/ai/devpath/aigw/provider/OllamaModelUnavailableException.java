package ai.devpath.aigw.provider;

/**
 * Ollama 는 응답하지만 설정한 모델이 없다(스팟 회수 뒤 PVC 유실, 다운로드 중 등). 내용 실패가 아니라
 * 그 Ollama 를 지금 쓸 수 없다는 <b>가용성 실패</b>다 — {@link ProviderFailures} 가 TRANSIENT 로 분류한다.
 */
public class OllamaModelUnavailableException extends RuntimeException {

  public OllamaModelUnavailableException(String message) {
    super(message);
  }
}
