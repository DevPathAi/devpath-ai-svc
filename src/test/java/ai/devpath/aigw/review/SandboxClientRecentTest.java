package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxClientRecentTest {

  private MockWebServer server;
  private SandboxClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new SandboxClient(server.url("/").toString(), Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() throws Exception { server.shutdown(); }

  @Test
  void recentByUserParsesSessionList() {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
        .setBody("[{\"id\":2,\"userId\":42,\"language\":\"PYTHON\",\"contentId\":null,"
            + "\"submittedCode\":\"print(2)\",\"stdout\":\"2\",\"stderr\":\"\",\"exitCode\":0,"
            + "\"status\":\"COMPLETED\"}]"));

    List<SandboxSessionView> recent = client.recentByUser(42L, 5);

    assertThat(recent).hasSize(1);
    assertThat(recent.get(0).submittedCode()).isEqualTo("print(2)");
  }

  @Test
  void recentByUserReturnsEmptyOnError() {
    server.enqueue(new MockResponse().setResponseCode(500));
    assertThat(client.recentByUser(42L, 5)).isEmpty();
  }
}
