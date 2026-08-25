package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaMentorClientTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception { server = new MockWebServer(); server.start(); }

  @AfterEach
  void tearDown() throws Exception { server.shutdown(); }

  @Test
  void streamsContentDeltasFromNdjson() {
    // Ollama /api/chat stream:true → 줄당 한 JSON 객체(NDJSON).
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/x-ndjson")
        .setBody("{\"message\":{\"content\":\"비동기는 \"},\"done\":false}\n"
            + "{\"message\":{\"content\":\"Future입니다.\"},\"done\":false}\n"
            + "{\"message\":{\"content\":\"\"},\"done\":true}\n"));
    var client = new OllamaMentorClient(server.url("/").toString(), "qwen2.5:7b",
        Duration.ofSeconds(60), new MentorPromptBuilder(), JsonMapper.builder().build());

    List<String> tokens = new ArrayList<>();
    client.stream(new MentorInput("비동기란?", "ctx"), tokens::add);

    assertThat(client.providerName()).isEqualTo("OLLAMA");
    assertThat(String.join("", tokens)).isEqualTo("비동기는 Future입니다.");
  }

  @Test
  void sendsLengthDelimitedJsonAcceptedByTheReleaseTlsProxy() throws Exception {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/x-ndjson")
        .setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}\n"));
    var client = new OllamaMentorClient(server.url("/").toString(), "qwen2.5:3b",
        Duration.ofSeconds(1), new MentorPromptBuilder(), JsonMapper.builder().build());

    client.stream(new MentorInput("q", ""), ignored -> {});

    var request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getHeader("Content-Length")).isNotBlank();
    assertThat(request.getHeader("Transfer-Encoding")).isNull();
  }

  @Test
  void transportStopsAtProviderDeadlineBeforeTheRequestDeadline() {
    MentorTimeoutPolicy policy = new MentorTimeoutPolicy(
        Duration.ofMillis(150), Duration.ofSeconds(1), Duration.ofSeconds(2));
    server.enqueue(new MockResponse()
        .setHeadersDelay(2, TimeUnit.SECONDS)
        .setBody("{\"done\":true}\n"));
    var client = new OllamaMentorClient(server.url("/").toString(), "qwen2.5:3b",
        policy.providerTimeout(), new MentorPromptBuilder(), JsonMapper.builder().build());
    long started = System.nanoTime();

    assertThatThrownBy(() -> client.stream(new MentorInput("q", ""), ignored -> {}))
        .isInstanceOf(RuntimeException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(policy.requestTimeout());
  }

  @Test
  void rejectsNonSuccessHttpStatusWithoutReadingOrLeakingProviderBody() {
    server.enqueue(new MockResponse()
        .setResponseCode(503)
        .setBody("raw current_code=SYNTHETIC_SECRET"));
    var client = new OllamaMentorClient(server.url("/").toString(), "qwen2.5:3b",
        Duration.ofSeconds(1), new MentorPromptBuilder(), JsonMapper.builder().build());

    assertThatThrownBy(() -> client.stream(new MentorInput("q", ""), ignored -> {}))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("mentor provider returned non-success status")
        .hasMessageNotContaining("SYNTHETIC_SECRET");
  }

  @Test
  void rejectsProviderEofWithoutDoneAndPreservesAlreadyDeliveredTokens() {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/x-ndjson")
        .setBody("{\"message\":{\"content\":\"partial\"},\"done\":false}\n"));
    var client = new OllamaMentorClient(server.url("/").toString(), "qwen2.5:3b",
        Duration.ofSeconds(1), new MentorPromptBuilder(), JsonMapper.builder().build());
    List<String> tokens = new ArrayList<>();

    assertThatThrownBy(() -> client.stream(new MentorInput("q", ""), tokens::add))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("mentor provider stream ended before done");
    assertThat(tokens).containsExactly("partial");
  }
}
