package ai.devpath.aigw.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class OllamaControllerTest {

  private static final MockWebServer OLLAMA = startServer();

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("devpath.ollama.base-url", () -> OLLAMA.url("/").toString());
    registry.add("devpath.ollama.timeout", () -> "PT0.2S");
  }

  @AfterAll
  static void shutdown() throws IOException {
    OLLAMA.shutdown();
  }

  @Test
  void embedDelegatesToOllamaEmbedWithInputArray() throws Exception {
    OLLAMA.enqueue(jsonResponse(embedBody(vector(768))));

    MvcResult response = post("/ai/embed", Map.of("texts", List.of("hello")));

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("\"embeddings\""));
    var recorded = OLLAMA.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/api/embed", recorded.getPath());
    String body = recorded.getBody().readUtf8();
    assertTrue(body.contains("\"model\":\"nomic-embed-text\""));
    assertTrue(body.contains("\"input\":[\"hello\"]"));
  }

  @Test
  void embedRejectsWrongEmbeddingDimension() throws Exception {
    OLLAMA.enqueue(jsonResponse(embedBody(List.of(0.1, 0.2))));

    MvcResult response = post("/ai/embed", Map.of("texts", List.of("hello")));

    assertEquals(HttpStatus.BAD_GATEWAY.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("768"));
    assertEquals("/api/embed", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void embedMapsOllama5xxToServiceUnavailable() throws Exception {
    OLLAMA.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    MvcResult response = post("/ai/embed", Map.of("texts", List.of("hello")));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getResponse().getStatus());
    assertEquals("/api/embed", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void embedMapsTimeoutToServiceUnavailable() throws Exception {
    OLLAMA.enqueue(jsonResponse(embedBody(vector(768))).setBodyDelay(1, TimeUnit.SECONDS));

    MvcResult response = post("/ai/embed", Map.of("texts", List.of("hello")));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getResponse().getStatus());
    assertEquals("/api/embed", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathSendsNonStreamingStructuredChatRequest() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("\"expectedOutcome\""));
    var recorded = OLLAMA.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/api/chat", recorded.getPath());
    String body = recorded.getBody().readUtf8();
    assertTrue(body.contains("\"model\":\"qwen2.5:7b\""));
    assertTrue(body.contains("\"stream\":false"));
    assertTrue(body.contains("\"format\""));
    assertTrue(body.contains("\"temperature\":0.2"));
  }

  @Test
  void generatePathRetriesMalformedContentOnceThenReturnsBadGateway() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody("not-json")));
    OLLAMA.enqueue(jsonResponse(chatBody("still-not-json")));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.BAD_GATEWAY.value(), response.getResponse().getStatus());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  private MvcResult post(String path, Map<String, ?> body) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(body)))
        .andReturn();
  }

  private static MockWebServer startServer() {
    try {
      MockWebServer server = new MockWebServer();
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private String embedBody(List<Double> embedding) throws Exception {
    return jsonMapper.writeValueAsString(Map.of(
        "model", "nomic-embed-text",
        "embeddings", List.of(embedding)
    ));
  }

  private String chatBody(String content) throws Exception {
    return jsonMapper.writeValueAsString(Map.of(
        "message", Map.of("role", "assistant", "content", content),
        "done", true
    ));
  }

  private String validPathContent() throws Exception {
    return jsonMapper.writeValueAsString(Map.of(
        "rationale", "백엔드 기초를 먼저 다진 뒤 실전 과제로 확장합니다.",
        "milestones", List.of(Map.of(
            "weekNum", 1,
            "title", "Spring Boot foundations",
            "goalDescription", "REST API와 테스트 기본기를 익힙니다.",
            "targetSkills", List.of("Spring MVC", "JUnit"),
            "estimatedHours", 6,
            "whyThisOrder", "진단 결과에서 API 설계가 약점으로 확인되었습니다.",
            "expectedOutcome", "간단한 CRUD API를 테스트와 함께 만들 수 있습니다.",
            "tasks", List.of(
                Map.of("orderNum", 1, "taskType", "READ", "title", "Spring MVC 읽기", "required", true),
                Map.of("orderNum", 2, "taskType", "PRACTICE", "title", "Controller 작성", "required", true),
                Map.of("orderNum", 3, "taskType", "QUIZ", "title", "HTTP 상태코드 점검", "required", false),
                Map.of("orderNum", 4, "taskType", "READ", "title", "추가 읽기", "required", false)
            )
        ))
    ));
  }

  private static Map<String, Object> pathRequest() {
    return Map.of(
        "track", "BACKEND",
        "diagnosedLevel", "JUNIOR",
        "strengthConcepts", List.of("Java"),
        "weaknessConcepts", List.of("Spring MVC"),
        "goal", "취업 준비"
    );
  }

  private static List<Double> vector(int size) {
    return IntStream.range(0, size).mapToObj(i -> 0.01d).toList();
  }
}
