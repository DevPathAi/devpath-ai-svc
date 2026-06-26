package ai.devpath.aigw.community;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Ollama 시드 클라이언트 단위(MockWebServer, /api/chat stream:false 자유텍스트). */
class OllamaSeedClientTest {

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
  void parsesFreeTextAnswerFromOllama() {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"message\":{\"content\":\"비동기는 Future로 다룹니다.\"}}"));
    var client = new OllamaSeedClient(server.url("/").toString(), "qwen2.5:7b",
        Duration.ofSeconds(30), new SeedPromptBuilder(), JsonMapper.builder().build());

    SeedAnswer answer = client.generate(new SeedInput("비동기란?", "헷갈립니다."));

    assertThat(client.providerName()).isEqualTo("OLLAMA");
    assertThat(answer.content()).contains("비동기");
  }

  @Test
  void emptyContentThrows() {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"message\":{\"content\":\"\"}}"));
    var client = new OllamaSeedClient(server.url("/").toString(), "qwen2.5:7b",
        Duration.ofSeconds(5), new SeedPromptBuilder(), JsonMapper.builder().build());

    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> client.generate(new SeedInput("q", "b")));
  }

  @Test
  void serverErrorThrows() {
    server.enqueue(new MockResponse().setResponseCode(503));
    var client = new OllamaSeedClient(server.url("/").toString(), "qwen2.5:7b",
        Duration.ofSeconds(5), new SeedPromptBuilder(), JsonMapper.builder().build());

    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> client.generate(new SeedInput("q", "b")));
  }
}
