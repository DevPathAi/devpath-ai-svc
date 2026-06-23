package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaAiReviewClientTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void parsesStructuredReviewFromOllama() {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"message\":{\"content\":\"{\\\"confidence\\\":90,"
            + "\\\"strengths\\\":[\\\"clear\\\"],\\\"improvements\\\":[],"
            + "\\\"security\\\":[{\\\"message\\\":\\\"no input validation\\\","
            + "\\\"severity\\\":\\\"warning\\\"}]}\"}}"));
    var client = new OllamaAiReviewClient(server.url("/").toString(), "qwen2.5-coder:7b",
        Duration.ofSeconds(30), new ReviewPromptBuilder(), JsonMapper.builder().build());

    ReviewResult r = client.review(new ReviewInput("PYTHON", "print(1)", "1\n", "", 0));

    assertThat(client.providerName()).isEqualTo("OLLAMA");
    assertThat(r.confidence()).isEqualTo(90);
    assertThat(r.strengths()).containsExactly("clear");
    assertThat(r.security()).hasSize(1);
    assertThat(r.security().get(0).severity()).isEqualTo("warning");
  }

  @Test
  void serverErrorMapsToTransient5xx() {
    server.enqueue(new MockResponse().setResponseCode(503));
    var client = new OllamaAiReviewClient(server.url("/").toString(), "qwen2.5-coder:7b",
        Duration.ofSeconds(5), new ReviewPromptBuilder(), JsonMapper.builder().build());

    TransientReviewException ex = org.junit.jupiter.api.Assertions.assertThrows(
        TransientReviewException.class,
        () -> client.review(new ReviewInput("PYTHON", "x", "", "", 0)));
    assertThat(ex.errorCode()).isEqualTo("LLM_5XX");
  }

  @Test
  void rateLimitMapsToTransientRateLimit() {
    server.enqueue(new MockResponse().setResponseCode(429));
    var client = new OllamaAiReviewClient(server.url("/").toString(), "qwen2.5-coder:7b",
        Duration.ofSeconds(5), new ReviewPromptBuilder(), JsonMapper.builder().build());

    TransientReviewException ex = org.junit.jupiter.api.Assertions.assertThrows(
        TransientReviewException.class,
        () -> client.review(new ReviewInput("PYTHON", "x", "", "", 0)));
    assertThat(ex.errorCode()).isEqualTo("LLM_RATELIMIT");
  }

  @Test
  void malformedJsonMapsToPermanentParseFailed() {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
        .setBody("{\"message\":{\"content\":\"not-json\"}}"));
    var client = new OllamaAiReviewClient(server.url("/").toString(), "qwen2.5-coder:7b",
        Duration.ofSeconds(5), new ReviewPromptBuilder(), JsonMapper.builder().build());

    PermanentReviewException ex = org.junit.jupiter.api.Assertions.assertThrows(
        PermanentReviewException.class,
        () -> client.review(new ReviewInput("PYTHON", "x", "", "", 0)));
    assertThat(ex.errorCode()).isEqualTo("PARSE_FAILED");
  }
}
