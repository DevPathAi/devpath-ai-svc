package ai.devpath.aigw.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
  void embedRejectsMismatchedEmbeddingCount() throws Exception {
    OLLAMA.enqueue(jsonResponse(jsonMapper.writeValueAsString(Map.of(
        "embeddings", List.of(vector(768), vector(768))
    ))));

    MvcResult response = post("/ai/embed", Map.of("texts", List.of("hello")));

    assertEquals(HttpStatus.BAD_GATEWAY.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("개수"));
    assertEquals("/api/embed", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void embedRejectsInvalidRequestBeforeCallingOllama() throws Exception {
    MvcResult emptyTexts = post("/ai/embed", Map.of("texts", List.of()));
    MvcResult blankText = post("/ai/embed", Map.of("texts", List.of("")));

    assertEquals(HttpStatus.BAD_REQUEST.value(), emptyTexts.getResponse().getStatus());
    assertEquals(HttpStatus.BAD_REQUEST.value(), blankText.getResponse().getStatus());
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
    assertTrue(body.contains("exactly 3 practical, concise tasks"));
    assertTrue(body.contains("BACKEND_SPRING"));
    assertTrue(body.contains("JUNIOR"));
    assertTrue(body.contains("Spring MVC"));
  }

  /**
   * 서비스의 콘텐츠·문항·멘토 응답은 모두 한국어인데 학습경로만 영어로 나왔다(2026-08-17 로컬 실측).
   * 프롬프트에 언어 지시가 없었기 때문이다. taskType 값은 계약상 ASCII 그대로 유지해야 한다.
   */
  @Test
  void generatePathPromptRequiresKoreanProseAndAsciiTaskTypes() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())));

    post("/ai/path/generate", pathRequest());

    String body = OLLAMA.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
    assertTrue(body.contains("in Korean"), "프롬프트가 한국어 출력을 지시해야 한다");
    assertTrue(body.contains("READ"), "taskType 값은 영문 그대로 유지되어야 한다");
  }

  /** 12주 로드맵을 약속하고 total_weeks 도 12로 저장하므로, 주차 수를 스키마에서 고정한다. */
  @Test
  void generatePathSchemaPinsTwelveMilestones() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())));

    post("/ai/path/generate", pathRequest());

    String body = OLLAMA.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
    assertTrue(body.contains("\"minItems\":12"), "milestones 최소 12개를 스키마로 고정해야 한다");
    assertTrue(body.contains("\"maxItems\":12"), "milestones 최대 12개를 스키마로 고정해야 한다");
  }

  /**
   * 로컬 실측에서 qwen2.5:14b 가 weekNum 1·2·4 로 3개만 냈는데 계약이 통과시켰다.
   * total_weeks 는 12로 저장되므로 3주짜리 경로가 12주로 표시된다.
   */
  @Test
  void generatePathRejectsIncompleteWeekCoverage() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithWeeks(List.of(1, 2, 4), validTasks()))));
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithWeeks(List.of(1, 2, 4), validTasks()))));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.BAD_GATEWAY.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("12"));
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  /** 주차 번호가 중복되면 12개여도 12주를 덮지 못한다. */
  @Test
  void generatePathRejectsDuplicatedWeekNumbers() throws Exception {
    List<Integer> duplicated = new java.util.ArrayList<>(IntStream.rangeClosed(1, 11).boxed().toList());
    duplicated.add(11);
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithWeeks(duplicated, validTasks()))));
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithWeeks(duplicated, validTasks()))));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.BAD_GATEWAY.value(), response.getResponse().getStatus());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  private List<Map<String, Object>> validTasks() {
    return List.of(
        task(1, "READ", "Spring MVC 읽기", true),
        task(2, "PRACTICE", "Controller 작성", true),
        task(3, "QUIZ", "HTTP 상태코드 점검", false)
    );
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

  @Test
  void generatePathRetriesMalformedContentOnceThenSucceeds() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody("not-json")));
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("\"expectedOutcome\""));
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathRetriesEmptyContentOnceThenSucceeds() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody("")));
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("\"expectedOutcome\""));
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathRejectsTooFewTasks() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithTasks(List.of(
        task(1, "READ", "Spring MVC 읽기", true),
        task(2, "PRACTICE", "Controller 작성", true)
    )))));
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithTasks(List.of(
        task(1, "READ", "Spring MVC 읽기", true),
        task(2, "PRACTICE", "Controller 작성", true)
    )))));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.BAD_GATEWAY.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("3"));
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathRejectsInvalidTaskType() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithTasks(List.of(
        task(1, "WATCH", "영상 보기", true),
        task(2, "PRACTICE", "Controller 작성", true),
        task(3, "QUIZ", "HTTP 상태코드 점검", false)
    )))));
    OLLAMA.enqueue(jsonResponse(chatBody(pathContentWithTasks(List.of(
        task(1, "WATCH", "영상 보기", true),
        task(2, "PRACTICE", "Controller 작성", true),
        task(3, "QUIZ", "HTTP 상태코드 점검", false)
    )))));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.BAD_GATEWAY.value(), response.getResponse().getStatus());
    assertTrue(response.getResponse().getContentAsString().contains("task_type"));
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathNormalizesExtraTasksToTopThree() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
    String body = response.getResponse().getContentAsString();
    assertTrue(body.contains("HTTP 상태코드 점검"));
    assertFalse(body.contains("추가 읽기"));
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathMapsOllama5xxToServiceUnavailable() throws Exception {
    OLLAMA.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getResponse().getStatus());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathMapsTimeoutToServiceUnavailable() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())).setBodyDelay(1, TimeUnit.SECONDS));

    MvcResult response = post("/ai/path/generate", pathRequest());

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getResponse().getStatus());
    assertEquals("/api/chat", OLLAMA.takeRequest(1, TimeUnit.SECONDS).getPath());
  }

  @Test
  void generatePathRejectsInvalidRequestBeforeCallingOllama() throws Exception {
    MvcResult blankTrack = post("/ai/path/generate", Map.of(
        "track", "",
        "diagnosedLevel", "JUNIOR",
        "strengthConcepts", List.of("Java"),
        "weaknessConcepts", List.of("Spring MVC")
    ));
    MvcResult blankConcept = post("/ai/path/generate", Map.of(
        "track", "BACKEND_SPRING",
        "diagnosedLevel", "JUNIOR",
        "strengthConcepts", List.of(""),
        "weaknessConcepts", List.of("Spring MVC")
    ));

    assertEquals(HttpStatus.BAD_REQUEST.value(), blankTrack.getResponse().getStatus());
    assertEquals(HttpStatus.BAD_REQUEST.value(), blankConcept.getResponse().getStatus());
  }

  @Test
  void generatePathAllowsEmptyConceptLists() throws Exception {
    OLLAMA.enqueue(jsonResponse(chatBody(validPathContent())));

    MvcResult response = post("/ai/path/generate", Map.of(
        "track", "BACKEND_SPRING",
        "diagnosedLevel", "JUNIOR",
        "strengthConcepts", List.of(),
        "weaknessConcepts", List.of()
    ));

    assertEquals(HttpStatus.OK.value(), response.getResponse().getStatus());
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
    return pathContentWithTasks(List.of(
        task(1, "READ", "Spring MVC 읽기", true),
        task(2, "PRACTICE", "Controller 작성", true),
        task(3, "QUIZ", "HTTP 상태코드 점검", false),
        task(4, "READ", "추가 읽기", false)
    ));
  }

  private String pathContentWithTasks(List<Map<String, Object>> tasks) throws Exception {
    return pathContent(IntStream.rangeClosed(1, 12).boxed().toList(), tasks);
  }

  private String pathContentWithWeeks(List<Integer> weekNums, List<Map<String, Object>> tasks) throws Exception {
    return pathContent(weekNums, tasks);
  }

  private String pathContent(List<Integer> weekNums, List<Map<String, Object>> tasks) throws Exception {
    return jsonMapper.writeValueAsString(Map.of(
        "rationale", "백엔드 기초를 먼저 다진 뒤 실전 과제로 확장합니다.",
        "milestones", weekNums.stream().map(week -> Map.<String, Object>of(
            "weekNum", week,
            "title", week + "주차 Spring Boot foundations",
            "goalDescription", "REST API와 테스트 기본기를 익힙니다.",
            "targetSkills", List.of("Spring MVC", "JUnit"),
            "estimatedHours", 6,
            "whyThisOrder", "진단 결과에서 API 설계가 약점으로 확인되었습니다.",
            "expectedOutcome", "간단한 CRUD API를 테스트와 함께 만들 수 있습니다.",
            "tasks", tasks
        )).toList()
    ));
  }

  private static Map<String, Object> task(int orderNum, String taskType, String title, boolean required) {
    return Map.of(
        "orderNum", orderNum,
        "taskType", taskType,
        "title", title,
        "required", required
    );
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

  private static List<Double> vector(int size) {
    return IntStream.range(0, size).mapToObj(i -> 0.01d).toList();
  }
}
