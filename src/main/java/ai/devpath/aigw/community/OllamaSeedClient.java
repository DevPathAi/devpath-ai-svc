package ai.devpath.aigw.community;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * dev 커뮤니티 시드 클라이언트(Ollama, /api/chat stream:false 자유 텍스트 답변).
 * 질문은 SeedPromptBuilder가 &lt;user_question&gt; 델리미터로 격리(인젝션 방어).
 */
@Component
@ConditionalOnProperty(name = "devpath.community-seed.provider", havingValue = "ollama")
public class OllamaSeedClient implements AiSeedClient {

  private final RestClient restClient;
  private final String model;
  private final SeedPromptBuilder prompts;

  public OllamaSeedClient(
      @Value("${devpath.ollama.base-url:http://localhost:11434}") String baseUrl,
      @Value("${devpath.community-seed.ollama-model:qwen2.5:7b}") String model,
      @Value("${devpath.community-seed.ollama-timeout:PT60S}") Duration timeout,
      SeedPromptBuilder prompts, tools.jackson.databind.json.JsonMapper jsonMapper) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.model = model;
    this.prompts = prompts;
  }

  @Override
  public SeedAnswer generate(SeedInput input) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(
        Map.of("role", "system", "content", prompts.systemPrompt()),
        Map.of("role", "user", "content", prompts.userContent(input))));
    body.put("stream", false);
    body.put("options", Map.of("temperature", 0.4));

    OllamaChatResponse response;
    try {
      response = restClient.post().uri("/api/chat").body(body)
          .retrieve().body(OllamaChatResponse.class);
    } catch (RestClientException e) {
      throw new SeedGenerationException("LLM_FAILED", "Ollama seed 호출 실패", e);
    }
    if (response == null || response.message() == null
        || response.message().content() == null || response.message().content().isBlank()) {
      throw new SeedGenerationException("LLM_FAILED", "Ollama seed 응답이 비어 있습니다", null);
    }
    return new SeedAnswer(response.message().content());
  }

  @Override
  public String providerName() { return "OLLAMA"; }

  private record OllamaChatResponse(OllamaMessage message) {}

  private record OllamaMessage(String content) {}
}
