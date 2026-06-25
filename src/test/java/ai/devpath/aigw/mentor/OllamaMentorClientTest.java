package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
}
