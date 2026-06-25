package ai.devpath.aigw.mentor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** dev 멘토(Ollama, /api/chat stream:true NDJSON 델타). 인젝션 방어는 MentorPromptBuilder. */
@Component
@ConditionalOnProperty(name = "devpath.mentor.provider", havingValue = "ollama")
public class OllamaMentorClient implements AiMentorClient {

  private final RestClient restClient;
  private final String model;
  private final MentorPromptBuilder prompts;
  private final JsonMapper jsonMapper;

  public OllamaMentorClient(
      @Value("${devpath.ollama.base-url:http://localhost:11434}") String baseUrl,
      @Value("${devpath.mentor.ollama-model:qwen2.5:7b}") String model,
      @Value("${devpath.mentor.timeout:PT60S}") Duration timeout,
      MentorPromptBuilder prompts, JsonMapper jsonMapper) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.model = model;
    this.prompts = prompts;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void stream(MentorInput input, Consumer<String> tokenSink) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(
        Map.of("role", "system", "content", prompts.systemPrompt()),
        Map.of("role", "user", "content", prompts.userContent(input))));
    body.put("stream", true);
    body.put("options", Map.of("temperature", 0.4));

    restClient.post().uri("/api/chat").body(body).exchange((req, res) -> {
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          JsonNode node = jsonMapper.readTree(line);
          String delta = node.path("message").path("content").asString("");
          if (!delta.isEmpty()) tokenSink.accept(delta);
          if (node.path("done").asBoolean(false)) break;
        }
      }
      return null;
    });
  }

  @Override
  public String providerName() { return "OLLAMA"; }
}
