package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LearningClientTest {

  private MockWebServer server;
  private LearningClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new LearningClient(server.url("/").toString(), Duration.ofSeconds(5),
        JsonMapper.builder().build());
  }

  @AfterEach
  void tearDown() throws Exception { server.shutdown(); }

  @Test
  void getContentParsesView() {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
        .setBody("{\"id\":7,\"slug\":\"async\",\"title\":\"비동기\",\"track\":\"BACKEND_SPRING\",\"body\":\"본문\"}"));

    InternalContentView v = client.getContent(7).orElseThrow();

    assertThat(v.id()).isEqualTo(7);
    assertThat(v.track()).isEqualTo("BACKEND_SPRING");
    assertThat(v.body()).isEqualTo("본문");
  }

  @Test
  void getContentReturnsEmptyOn404() {
    server.enqueue(new MockResponse().setResponseCode(404));
    assertThat(client.getContent(999)).isEmpty();
  }

  @Test
  void searchSimilarParsesList() {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
        .setBody("[{\"contentId\":1,\"slug\":\"a\",\"title\":\"t1\"},"
            + "{\"contentId\":2,\"slug\":\"b\",\"title\":\"t2\"}]"));

    List<SimilarContent> refs = client.searchSimilar(Collections.nCopies(768, 0.1), 3, "BACKEND_SPRING");

    assertThat(refs).hasSize(2);
    assertThat(refs.get(0).slug()).isEqualTo("a");
  }

  @Test
  void searchSimilarReturnsEmptyOnError() {
    server.enqueue(new MockResponse().setResponseCode(500));
    assertThat(client.searchSimilar(Collections.nCopies(768, 0.1), 3, null)).isEmpty();
  }
}
