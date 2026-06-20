package ai.devpath.aigw.ollama;

import ai.devpath.aigw.ollama.dto.EmbedRequest;
import ai.devpath.aigw.ollama.dto.EmbedResponse;
import ai.devpath.aigw.ollama.dto.PathGenerateRequest;
import ai.devpath.aigw.ollama.dto.PathGenerateResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class OllamaController {

  private final OllamaClient ollama;

  public OllamaController(OllamaClient ollama) {
    this.ollama = ollama;
  }

  @PostMapping("/embed")
  public ResponseEntity<EmbedResponse> embed(@Valid @RequestBody EmbedRequest request) {
    return ResponseEntity.ok(ollama.embed(request.texts()));
  }

  @PostMapping("/path/generate")
  public ResponseEntity<PathGenerateResponse> generatePath(@Valid @RequestBody PathGenerateRequest request) {
    return ResponseEntity.ok(ollama.generatePath(request));
  }
}
