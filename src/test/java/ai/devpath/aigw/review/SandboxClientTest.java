package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxClientTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception { server = new MockWebServer(); server.start(); }

  @AfterEach
  void tearDown() throws Exception { server.shutdown(); }

  @Test
  void fetchesSessionView() throws Exception {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"id\":7,\"userId\":42,\"language\":\"PYTHON\",\"contentId\":3,"
            + "\"submittedCode\":\"print(1)\",\"stdout\":\"1\\n\",\"stderr\":\"\","
            + "\"exitCode\":0,\"status\":\"COMPLETED\"}"));
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    SandboxSessionView v = client.getSession(7);

    assertThat(v.userId()).isEqualTo(42L);
    assertThat(v.submittedCode()).isEqualTo("print(1)");
    assertThat(v.exitCode()).isEqualTo(0);
    assertThat(server.takeRequest().getHeader("X-DevPath-Internal-Token"))
        .isEqualTo("test-internal-token");
  }

  @Test
  void serverErrorThrowsUnavailable() {
    server.enqueue(new MockResponse().setResponseCode(500));
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    assertThatThrownBy(() -> client.getSession(7))
        .isInstanceOf(SandboxUnavailableException.class);
  }

  @Test
  void missingOrBlankInternalTokenFailsBeforeAnyHttpRequest() {
    for (String token : new String[] {null, "", "  \t"}) {
      assertThatThrownBy(() -> new SandboxClient(
          server.url("/").toString(), Duration.ofSeconds(5), token))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("sandbox internal token is required");
    }
    assertThat(server.getRequestCount()).isZero();
  }
}
