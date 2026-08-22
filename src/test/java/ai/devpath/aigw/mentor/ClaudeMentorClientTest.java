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

class ClaudeMentorClientTest {

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
  void providerNameIsClaude() {
    var client = new ClaudeMentorClient(null, "claude-sonnet-4-6", new MentorPromptBuilder());
    assertThat(client.providerName()).isEqualTo("CLAUDE");
  }

  @Test
  void sdkTransportStopsAtTheSameProviderDeadlineBeforeRequestDeadline() {
    MentorTimeoutPolicy policy = new MentorTimeoutPolicy(
        Duration.ofMillis(150), Duration.ofSeconds(1), Duration.ofSeconds(2));
    server.enqueue(new MockResponse()
        .setHeadersDelay(2, TimeUnit.SECONDS)
        .setBody("{}"));
    var sdk = MentorClaudeClientConfig.buildClient(
        "synthetic-test-key", server.url("/").toString(), policy.providerTimeout());
    var client = new ClaudeMentorClient(sdk, "claude-sonnet-4-6", new MentorPromptBuilder());
    long started = System.nanoTime();

    assertThatThrownBy(() -> client.stream(new MentorInput("q", ""), ignored -> {}))
        .isInstanceOf(RuntimeException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(policy.requestTimeout());
  }

  @Test
  void rejectsProviderEofWithoutMessageStopAndPreservesDeliveredTokens() {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(claudeStream(false)));
    var sdk = MentorClaudeClientConfig.buildClient(
        "synthetic-test-key", server.url("/").toString(), Duration.ofSeconds(2));
    var client = new ClaudeMentorClient(sdk, "claude-sonnet-4-6", new MentorPromptBuilder());
    List<String> tokens = new ArrayList<>();

    assertThatThrownBy(() -> client.stream(new MentorInput("q", ""), tokens::add))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("mentor provider stream ended before message_stop");
    assertThat(tokens).containsExactly("partial");
  }

  @Test
  void acceptsStreamOnlyAfterExplicitMessageStop() {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(claudeStream(true)));
    var sdk = MentorClaudeClientConfig.buildClient(
        "synthetic-test-key", server.url("/").toString(), Duration.ofSeconds(2));
    var client = new ClaudeMentorClient(sdk, "claude-sonnet-4-6", new MentorPromptBuilder());
    List<String> tokens = new ArrayList<>();

    client.stream(new MentorInput("q", ""), tokens::add);

    assertThat(tokens).containsExactly("partial");
  }

  private static String claudeStream(boolean includeStop) {
    String stream = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_synthetic","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-6","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}

        """;
    return includeStop ? stream + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n" : stream;
  }
}
