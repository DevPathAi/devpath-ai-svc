package ai.devpath.aigw.ollama;

public class OllamaContractException extends RuntimeException {
  public OllamaContractException(String message) {
    super(message);
  }

  public OllamaContractException(String message, Throwable cause) {
    super(message, cause);
  }
}
