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
 * 배포는 점 표기 속성이 아니라 환경 변수로 값을 넣는다. 그런데 이 서비스의 환경 변수는
 * 자동 완화 바인딩이 아니라 application.yml 의 자리표시자로 배선돼 있어서, 새 속성을 거기에
 * 등록하지 않으면 환경 변수를 아무리 넣어도 조용히 무시된다.
 *
 * <p>2026-08-17 실측에서 실제로 그랬다. OLLAMA_PATH_TIMEOUT 을 PT300S 로 주었는데 요청이
 * 정확히 8.1초에 503 으로 끊겼다 — 기본값 PT8S 가 그대로 쓰인 것이다. 점 표기 속성만 쓰는
 * 테스트는 이 결함을 보지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OllamaPathEnvWiringTest {

  private static final MockWebServer SHARED = startServer();
  private static final MockWebServer PATH = startServer();

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;

  @DynamicPropertySource
  static void environmentVariableNames(DynamicPropertyRegistry registry) {
    registry.add("OLLAMA_BASE_URL", () -> SHARED.url("/").toString());
    registry.add("OLLAMA_PATH_BASE_URL", () -> PATH.url("/").toString());
  }

  @AfterAll
  static void shutdown() throws IOException {
    SHARED.shutdown();
    PATH.shutdown();
  }

  @Test
  void pathBaseUrlEnvironmentVariableIsWiredThroughApplicationYaml() throws Exception {
    PATH.enqueue(jsonResponse(chatBody(validPathContent())));

    MvcResult response = mvc.perform(MockMvcRequestBuilders.post("/ai/path/generate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(pathRequest())))
        .andReturn();

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    assertEquals("/api/chat", PATH.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertNull(SHARED.takeRequest(200, TimeUnit.MILLISECONDS),
        "OLLAMA_PATH_BASE_URL 이 배선되지 않으면 생성이 공용 주소로 샌다");
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
