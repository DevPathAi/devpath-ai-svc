package ai.devpath.aigw.review;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * dev 코드리뷰(Ollama, 코드특화 모델 + 구조화 출력 format).
 * 사용자 코드는 ReviewPromptBuilder가 델리미터로 격리(인젝션 방어), 출력은 format JSON schema로 제약.
 */
@Component
@ConditionalOnProperty(name = "devpath.review.provider", havingValue = "ollama")
public class OllamaAiReviewClient implements AiReviewClient {

  private final RestClient restClient;
  private final String model;
  private final ReviewPromptBuilder prompts;
  private final JsonMapper jsonMapper;

  public OllamaAiReviewClient(
      @Value("${devpath.ollama.base-url:http://localhost:11434}") String baseUrl,
      @Value("${devpath.review.ollama-model:qwen2.5-coder:7b}") String model,
      @Value("${devpath.review.ollama-timeout:PT60S}") Duration timeout,
      ReviewPromptBuilder prompts, JsonMapper jsonMapper) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.model = model;
    this.prompts = prompts;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public ReviewResult review(ReviewInput input) {
    try {
      return callAndParse(input);
    } catch (ContractException firstFailure) {
      return callAndParse(input); // 1회 retry
    }
  }

  @Override
  public String providerName() {
    return "OLLAMA";
  }

  private ReviewResult callAndParse(ReviewInput input) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(
        Map.of("role", "system", "content", prompts.systemPrompt()),
        Map.of("role", "user", "content", prompts.userContent(input))));
    body.put("stream", false);
    body.put("format", prompts.reviewJsonSchema());
    body.put("options", Map.of("temperature", 0.2));

    OllamaChatResponse response;
    try {
      response = restClient.post().uri("/api/chat").body(body)
          .retrieve().body(OllamaChatResponse.class);
    } catch (RestClientException e) {
      throw new ContractException("Ollama review 호출 실패", e);
    }
    if (response == null || response.message() == null
        || response.message().content() == null || response.message().content().isBlank()) {
      throw new ContractException("Ollama review content가 비어 있습니다", null);
    }
    try {
      return jsonMapper.readValue(response.message().content(), ReviewResult.class);
    } catch (Exception e) {
      throw new ContractException("Ollama review JSON 파싱 실패", e);
    }
  }

  private static final class ContractException extends RuntimeException {
    ContractException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record OllamaChatResponse(OllamaMessage message) {}

  private record OllamaMessage(String content) {}
}
