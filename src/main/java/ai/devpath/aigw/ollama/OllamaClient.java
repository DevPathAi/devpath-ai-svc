package ai.devpath.aigw.ollama;

import ai.devpath.aigw.ollama.dto.EmbedResponse;
import ai.devpath.aigw.ollama.dto.PathGenerateRequest;
import ai.devpath.aigw.ollama.dto.PathGenerateResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OllamaClient {

  private static final int EMBEDDING_DIMENSIONS = 768;
  private static final int TASKS_PER_MILESTONE = 3;
  private static final List<String> TASK_TYPES = List.of("READ", "PRACTICE", "QUIZ");

  private final RestClient restClient;
  private final JsonMapper jsonMapper;
  private final String genModel;
  private final String embedModel;

  public OllamaClient(
      @Value("${devpath.ollama.base-url:http://localhost:11434}") String baseUrl,
      @Value("${devpath.ollama.gen-model:qwen2.5:7b}") String genModel,
      @Value("${devpath.ollama.embed-model:nomic-embed-text}") String embedModel,
      @Value("${devpath.ollama.timeout:PT8S}") Duration timeout,
      JsonMapper jsonMapper) {
    this.restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory(timeout))
        .build();
    this.jsonMapper = jsonMapper;
    this.genModel = genModel;
    this.embedModel = embedModel;
  }

  public EmbedResponse embed(List<String> texts) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", embedModel);
    body.put("input", texts);

    OllamaEmbedResponse response;
    try {
      response = restClient.post()
          .uri("/api/embed")
          .body(body)
          .retrieve()
          .body(OllamaEmbedResponse.class);
    } catch (RestClientException e) {
      throw new OllamaUnavailableException("Ollama embed 호출 실패", e);
    }

    if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
      throw new OllamaContractException("Ollama embed 응답 개수가 요청과 다릅니다");
    }
    for (List<Double> embedding : response.embeddings()) {
      if (embedding == null || embedding.size() != EMBEDDING_DIMENSIONS) {
        throw new OllamaContractException("Ollama embed 응답 차원이 768이 아닙니다");
      }
    }
    return new EmbedResponse(response.embeddings());
  }

  public PathGenerateResponse generatePath(PathGenerateRequest request) {
    OllamaChatResponse response = callChat(request);
    try {
      return parseAndNormalize(response);
    } catch (OllamaContractException firstFailure) {
      try {
        return parseAndNormalize(callChat(request));
      } catch (OllamaContractException secondFailure) {
        throw secondFailure;
      }
    }
  }

  private OllamaChatResponse callChat(PathGenerateRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", genModel);
    body.put("messages", List.of(
        Map.of("role", "system", "content", systemPrompt()),
        Map.of("role", "user", "content", userPrompt(request))
    ));
    body.put("stream", false);
    body.put("format", pathSchema());
    body.put("options", Map.of("temperature", 0.2));

    try {
      OllamaChatResponse response = restClient.post()
          .uri("/api/chat")
          .body(body)
          .retrieve()
          .body(OllamaChatResponse.class);
      if (response == null || response.message() == null || isBlank(response.message().content())) {
        throw new OllamaContractException("Ollama chat content가 비어 있습니다");
      }
      return response;
    } catch (OllamaContractException e) {
      throw e;
    } catch (RestClientException e) {
      throw new OllamaUnavailableException("Ollama chat 호출 실패", e);
    }
  }

  private PathGenerateResponse parseAndNormalize(OllamaChatResponse response) {
    try {
      PathGenerateResponse parsed = jsonMapper.readValue(response.message().content(), PathGenerateResponse.class);
      return normalize(validate(parsed));
    } catch (OllamaContractException e) {
      throw e;
    } catch (Exception e) {
      throw new OllamaContractException("Ollama path JSON 파싱 실패", e);
    }
  }

  private PathGenerateResponse validate(PathGenerateResponse response) {
    if (response == null || isBlank(response.rationale()) || response.milestones() == null
        || response.milestones().isEmpty()) {
      throw new OllamaContractException("Ollama path 응답 필수 필드가 없습니다");
    }
    for (PathGenerateResponse.Milestone milestone : response.milestones()) {
      if (milestone.weekNum() <= 0 || isBlank(milestone.title()) || isBlank(milestone.goalDescription())
          || milestone.targetSkills() == null || milestone.targetSkills().isEmpty()
          || milestone.estimatedHours() <= 0 || isBlank(milestone.whyThisOrder())
          || isBlank(milestone.expectedOutcome()) || milestone.tasks() == null || milestone.tasks().isEmpty()) {
        throw new OllamaContractException("Ollama milestone 응답 필수 필드가 없습니다");
      }
      if (milestone.tasks().size() < TASKS_PER_MILESTONE) {
        throw new OllamaContractException("Ollama milestone tasks는 최소 3개가 필요합니다");
      }
      for (PathGenerateResponse.Task task : milestone.tasks()) {
        if (task.orderNum() <= 0 || isBlank(task.taskType()) || isBlank(task.title())) {
          throw new OllamaContractException("Ollama task 응답 필수 필드가 없습니다");
        }
        if (!TASK_TYPES.contains(task.taskType())) {
          throw new OllamaContractException("Ollama task_type은 READ/PRACTICE/QUIZ 중 하나여야 합니다");
        }
      }
    }
    return response;
  }

  private PathGenerateResponse normalize(PathGenerateResponse response) {
    List<PathGenerateResponse.Milestone> milestones = new ArrayList<>();
    for (PathGenerateResponse.Milestone milestone : response.milestones()) {
      List<PathGenerateResponse.Task> tasks = milestone.tasks().size() > TASKS_PER_MILESTONE
          ? List.copyOf(milestone.tasks().subList(0, TASKS_PER_MILESTONE))
          : List.copyOf(milestone.tasks());
      milestones.add(new PathGenerateResponse.Milestone(
          milestone.weekNum(),
          milestone.title(),
          milestone.goalDescription(),
          List.copyOf(milestone.targetSkills()),
          milestone.estimatedHours(),
          milestone.whyThisOrder(),
          milestone.expectedOutcome(),
          tasks
      ));
    }
    return new PathGenerateResponse(response.rationale(), milestones);
  }

  private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    return factory;
  }

  private static String systemPrompt() {
    return """
        You generate a personalized 12-week software learning path.
        Return only JSON matching the provided schema.
        Each milestone must include exactly 3 practical, concise tasks.
        Do not include markdown fences or commentary.
        """;
  }

  private static String userPrompt(PathGenerateRequest request) {
    return """
        track: %s
        diagnosedLevel: %s
        strengthConcepts: %s
        weaknessConcepts: %s
        goal: %s
        """.formatted(
        request.track(),
        request.diagnosedLevel(),
        request.strengthConcepts(),
        request.weaknessConcepts(),
        request.goal() == null ? "" : request.goal());
  }

  private static Map<String, Object> pathSchema() {
    Map<String, Object> task = objectSchema(
        List.of("orderNum", "taskType", "title", "required"),
        Map.of(
            "orderNum", Map.of("type", "integer"),
            "taskType", Map.of("type", "string", "enum", List.of("READ", "PRACTICE", "QUIZ")),
            "title", Map.of("type", "string"),
            "required", Map.of("type", "boolean")
        ));
    Map<String, Object> milestone = objectSchema(
        List.of("weekNum", "title", "goalDescription", "targetSkills", "estimatedHours", "whyThisOrder",
            "expectedOutcome", "tasks"),
        Map.of(
            "weekNum", Map.of("type", "integer"),
            "title", Map.of("type", "string"),
            "goalDescription", Map.of("type", "string"),
            "targetSkills", Map.of("type", "array", "items", Map.of("type", "string")),
            "estimatedHours", Map.of("type", "integer"),
            "whyThisOrder", Map.of("type", "string"),
            "expectedOutcome", Map.of("type", "string"),
            "tasks", Map.of("type", "array", "minItems", 3, "maxItems", 3, "items", task)
        ));
    return objectSchema(
        List.of("rationale", "milestones"),
        Map.of(
            "rationale", Map.of("type", "string"),
            "milestones", Map.of("type", "array", "items", milestone)
        ));
  }

  private static Map<String, Object> objectSchema(List<String> required, Map<String, Object> properties) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("required", required);
    schema.put("properties", properties);
    return schema;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record OllamaEmbedResponse(List<List<Double>> embeddings) {
  }

  private record OllamaChatResponse(OllamaMessage message) {
  }

  private record OllamaMessage(String content) {
  }
}
