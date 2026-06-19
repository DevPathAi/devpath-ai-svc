package ai.devpath.aigw.ollama;

public class OllamaUnavailableException extends RuntimeException {
  public OllamaUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
