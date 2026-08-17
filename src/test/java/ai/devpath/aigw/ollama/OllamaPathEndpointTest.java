package ai.devpath.aigw.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * 학습경로 생성만 GPU 노드의 Ollama 로 보내고 임베딩·멘토·리뷰는 기존 CPU Ollama 에 남긴다.
 * 생성은 분 단위라 긴 타임아웃이 필요하지만 임베딩은 2초짜리라 같은 값을 쓰면 장애 감지가 늦어진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OllamaPathEndpointTest {

  private static final MockWebServer SHARED = startServer();
  private static final MockWebServer PATH = startServer();

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("devpath.ollama.base-url", () -> SHARED.url("/").toString());
    registry.add("devpath.ollama.path-base-url", () -> PATH.url("/").toString());
    registry.add("devpath.ollama.timeout", () -> "PT0.2S");
    registry.add("devpath.ollama.path-timeout", () -> "PT30S");
  }

  @AfterAll
  static void shutdown() throws IOException {
    SHARED.shutdown();
    PATH.shutdown();
  }

  @Test
  void pathGenerationGoesToTheDedicatedEndpoint() throws Exception {
    PATH.enqueue(jsonResponse(chatBody(validPathContent())));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    assertEquals("/api/chat", PATH.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertNull(SHARED.takeRequest(200, TimeUnit.MILLISECONDS), "공용 Ollama 로는 생성 요청이 가지 않아야 한다");
  }

  @Test
  void embedStaysOnTheSharedEndpoint() throws Exception {
    SHARED.enqueue(jsonResponse(embedBody(vector())));

    MvcResult response = post("/ai/embed", Map.of("texts", List.of("hello")));

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    assertEquals("/api/embed", SHARED.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertNull(PATH.takeRequest(200, TimeUnit.MILLISECONDS), "생성 전용 Ollama 로는 임베딩이 가지 않아야 한다");
  }

  /** 생성 타임아웃이 30초여도 임베딩은 제 짧은 타임아웃(0.2초)으로 실패해야 한다. */
  @Test
  void embedKeepsItsOwnShortTimeout() throws Exception {
    SHARED.enqueue(jsonResponse(embedBody(vector())).setBodyDelay(2, TimeUnit.SECONDS));

    long start = System.nanoTime();
    MvcResult response = post("/ai/embed", Map.of("texts", List.of("hello")));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getResponse().getStatus());
    org.junit.jupiter.api.Assertions.assertTrue(elapsedMs < 1_500,
        "임베딩이 생성 타임아웃까지 기다리면 안 된다: " + elapsedMs + "ms");
  }

  private MvcResult post(String path, Object body) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(body)))
        .andReturn();
  }

  private static MockWebServer startServer() {
    MockWebServer server = new MockWebServer();
    try {
      server.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return server;
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }

  private String chatBody(String content) throws Exception {
    return jsonMapper.writeValueAsString(Map.of("message", Map.of("role", "assistant", "content", content)));
  }

  private String embedBody(List<Double> embedding) throws Exception {
    return jsonMapper.writeValueAsString(Map.of("embeddings", List.of(embedding)));
  }

  private static List<Double> vector() {
    return java.util.Collections.nCopies(768, 0.1);
  }

  private static Map<String, Object> pathRequest() {
    return Map.of(
        "track", "BACKEND_SPRING",
        "diagnosedLevel", "JUNIOR",
        "strengthConcepts", List.of("Java"),
        "weaknessConcepts", List.of("Spring MVC"),
        "goal", "취업 준비"
    );
  }

  private String validPathContent() throws Exception {
    return jsonMapper.writeValueAsString(Map.of(
        "rationale", "백엔드 기초를 먼저 다진 뒤 실전 과제로 확장합니다.",
        "milestones", IntStream.rangeClosed(1, 12).mapToObj(week -> Map.<String, Object>of(
            "weekNum", week,
            "title", week + "주차 Spring Boot 기초",
            "goalDescription", "REST API와 테스트 기본기를 익힙니다.",
            "targetSkills", List.of("Spring MVC"),
            "estimatedHours", 6,
            "whyThisOrder", "약점부터 다집니다.",
            "expectedOutcome", "CRUD API를 만들 수 있습니다.",
            "tasks", List.of(
                Map.of("orderNum", 1, "taskType", "READ", "title", "개념 읽기", "required", true),
                Map.of("orderNum", 2, "taskType", "PRACTICE", "title", "직접 구현", "required", true),
                Map.of("orderNum", 3, "taskType", "QUIZ", "title", "점검 퀴즈", "required", false))
        )).toList()
    ));
  }
}
