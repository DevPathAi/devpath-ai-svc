package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * KnowledgeClient 직접 테스트(리뷰 Important #1). learning-svc(:8080)는 뜨지 않으므로 실제 네트워크 대신
 * 로컬 {@link MockWebServer}로 요청 바디 계약(embedding/limit)과 응답 역직렬화·실패 폴백을 검증한다.
 * LearningClientTest와 동일한 패턴(이 레포의 기존 선례)을 따른다.
 */
class KnowledgeClientTest {

  private MockWebServer server;
  private KnowledgeClient client;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new KnowledgeClient(server.url("/").toString(), Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void sendsEmbeddingAndLimitAsRequestBodyFields() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("[]"));

    client.searchSimilar(Collections.nCopies(768, 0.1), 3);

    RecordedRequest recorded = server.takeRequest();
    assertThat(recorded.getPath()).isEqualTo("/internal/knowledge/similar");
    @SuppressWarnings("unchecked")
    Map<String, Object> body =
        (Map<String, Object>) jsonMapper.readValue(recorded.getBody().readUtf8(), Map.class);
    assertThat(body).containsKey("embedding");
    assertThat(body.get("limit")).isEqualTo(3);
    assertThat((List<?>) body.get("embedding")).hasSize(768);
  }

  @Test
  void parsesAllFiveFieldsFromResponse() {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
        .setBody("[{\"docKey\":\"AWS/a.md\",\"title\":\"AWS 개념\",\"category\":\"AWS\","
            + "\"chunkText\":\"Pod Identity는 Fargate Pod를 지원하지 않는다\",\"distance\":0.12}]"));

    List<KnowledgeChunk> chunks = client.searchSimilar(Collections.nCopies(768, 0.1), 3);

    assertThat(chunks).hasSize(1);
    KnowledgeChunk chunk = chunks.get(0);
    assertThat(chunk.docKey()).isEqualTo("AWS/a.md");
    assertThat(chunk.title()).isEqualTo("AWS 개념");
    assertThat(chunk.category()).isEqualTo("AWS");
    assertThat(chunk.chunkText()).isEqualTo("Pod Identity는 Fargate Pod를 지원하지 않는다");
    assertThat(chunk.distance()).isEqualTo(0.12);
  }

  @Test
  void returnsEmptyListOnServerError() {
    server.enqueue(new MockResponse().setResponseCode(500));
    assertThat(client.searchSimilar(Collections.nCopies(768, 0.1), 3)).isEmpty();
  }

  @Test
  void returnsEmptyListOnConnectionFailure() throws Exception {
    server.shutdown(); // 연결 자체가 실패하는 경우(learning-svc 다운 시나리오)
    assertThat(client.searchSimilar(Collections.nCopies(768, 0.1), 3)).isEmpty();
  }

  @Test
  void returnsEmptyListWhenResponseBodyIsNullArray() {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("null"));
    assertThat(client.searchSimilar(Collections.nCopies(768, 0.1), 3)).isEmpty();
  }
}
