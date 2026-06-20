package ai.devpath.aigw.ollama;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OllamaExceptionHandler {

  @ExceptionHandler(OllamaContractException.class)
  public ResponseEntity<Map<String, String>> badGateway(OllamaContractException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(OllamaUnavailableException.class)
  public ResponseEntity<Map<String, String>> serviceUnavailable(OllamaUnavailableException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
  }
}
