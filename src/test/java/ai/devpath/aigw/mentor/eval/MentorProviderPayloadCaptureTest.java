package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.aigw.mentor.AiMentorClient;
import ai.devpath.aigw.mentor.ClaudeMentorClient;
import ai.devpath.aigw.mentor.MentorInput;
import ai.devpath.aigw.mentor.MentorPromptBuilder;
import ai.devpath.aigw.mentor.OllamaMentorClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Captures the actual provider HTTP bodies for every sensitive ON -> OFF sequence. */
class MentorProviderPayloadCaptureTest {

  private static final String MODEL = "synthetic-release-model";

  @Test
  void ollamaWireBodiesContainEachOnSentinelAndOmitItFromTheNextOffRequest()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      AiMentorClient client = new OllamaMentorClient(
          server.url("/").toString(), MODEL, Duration.ofSeconds(2),
          new MentorPromptBuilder(), JsonMapper.builder().build());

      assertCapturedPairs(server, client, true);
    }
  }

  @Test
  void claudeWireBodiesContainEachOnSentinelAndOmitItFromTheNextOffRequest()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      var sdk = AnthropicOkHttpClient.builder()
          .apiKey("synthetic-test-key")
          .baseUrl(server.url("/").toString())
          .timeout(Duration.ofSeconds(2))
          .maxRetries(0)
          .build();
      AiMentorClient client = new ClaudeMentorClient(sdk, MODEL, new MentorPromptBuilder());

      assertCapturedPairs(server, client, false);
    }
  }

  private void assertCapturedPairs(MockWebServer server, AiMentorClient client,
      boolean successfulResponse) throws Exception {
    List<MentorGoldenCase> cases = MentorGoldenCase.load(
        "/eval/golden-mentor-injection.jsonl");
    List<String> sequenceIds = MentorProviderPayloadContract
        .validate(cases, new MentorPromptBuilder()).stream()
        .map(MentorProviderPayloadContract.Result::sequenceId)
        .toList();

    for (String sequenceId : sequenceIds) {
      List<MentorGoldenCase> pair = cases.stream()
          .filter(goldenCase -> sequenceId.equals(goldenCase.sequenceId()))
          .sorted(Comparator.comparing(MentorGoldenCase::sequenceStep))
          .toList();
      String onBody = capture(server, client, pair.get(0), successfulResponse);
      String offBody = capture(server, client, pair.get(1), successfulResponse);
      String sentinel = pair.get(0).payloadSentinel();

      assertThat(onBody).contains(sentinel);
      assertThat(offBody).doesNotContain(sentinel);
    }
  }

  private String capture(MockWebServer server, AiMentorClient client,
      MentorGoldenCase goldenCase, boolean successfulResponse) throws Exception {
    if (successfulResponse) {
      server.enqueue(new MockResponse()
          .setHeader("Content-Type", "application/x-ndjson")
          .setBody("{\"message\":{\"content\":\"\"},\"done\":true}\n"));
      client.stream(input(goldenCase), ignored -> {});
    } else {
      server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
      assertThatThrownBy(() -> client.stream(input(goldenCase), ignored -> {}))
          .isInstanceOf(RuntimeException.class);
    }
    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    return request.getBody().readUtf8();
  }

  private MentorInput input(MentorGoldenCase goldenCase) {
    return new MentorInput(
        goldenCase.question(), goldenCase.context(), goldenCase.referenceDocs());
  }
}
