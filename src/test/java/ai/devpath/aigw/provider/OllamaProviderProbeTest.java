package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OllamaProviderProbeTest {

  private MockWebServer server;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stop() throws Exception {
    server.shutdown();
  }

  private static MockResponse tags(String... names) {
    StringBuilder models = new StringBuilder();
    for (String n : names) {
      if (models.length() > 0) models.append(',');
      models.append("{\"name\":\"").append(n).append("\",\"model\":\"").append(n)
          .append("\",\"size\":4683087332}");
    }
    return new MockResponse().setHeader("Content-Type", "application/json")
        .setBody("{\"models\":[" + models + "]}");
  }

  private OllamaProviderProbe probe(String model) {
    return new OllamaProviderProbe("review", server.url("/").toString(), model);
  }

  @Test
  void isALivenessTargetForOllama() {
    OllamaProviderProbe p = probe("qwen2.5:7b");
    assertEquals("review", p.feature());
    assertEquals("ollama", p.provider());
    assertTrue(p.livenessTarget());
  }

  @Test
  void passesWhenTheConfiguredModelIsLoaded() throws Exception {
    server.enqueue(tags("qwen2.5:3b", "qwen2.5:7b"));

    assertDoesNotThrow(() -> probe("qwen2.5:7b").ping());
    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("GET", request.getMethod());
    assertEquals("/api/tags", request.getPath());
  }

  @Test
  void failsAsAnAvailabilityFailureWhenTheModelIsMissing() {
    server.enqueue(tags("qwen2.5:3b"));

    RuntimeException e = assertThrows(RuntimeException.class, () -> probe("qwen2.5:7b").ping());
    assertEquals(FailureKind.TRANSIENT, ProviderFailures.classify(e).kind());
  }

  @Test
  void aSimilarTagDoesNotCountAsTheConfiguredModel() {
    // Review Focus 2: 정확히 같은 이름만 「있음」이다.
    server.enqueue(tags("qwen2.5:7b-instruct", "qwen2.5-coder:7b"));

    assertThrows(OllamaModelUnavailableException.class, () -> probe("qwen2.5:7b").ping());
  }

  @Test
  void aServerErrorIsTransient() {
    server.enqueue(new MockResponse().setResponseCode(503));

    RuntimeException e = assertThrows(RuntimeException.class, () -> probe("qwen2.5:7b").ping());
    assertEquals(FailureKind.TRANSIENT, ProviderFailures.classify(e).kind());
  }

  @Test
  void aRefusedConnectionIsTransient() throws Exception {
    // 공용 server 는 @AfterEach 가 닫으므로 따로 띄워 닫은 포트를 쓴다(연결 거부 = 회수된 GPU 노드).
    MockWebServer gone = new MockWebServer();
    gone.start();
    String dead = gone.url("/").toString();
    gone.shutdown();

    RuntimeException e = assertThrows(RuntimeException.class,
        () -> new OllamaProviderProbe("review", dead, "qwen2.5:7b").ping());
    assertEquals(FailureKind.TRANSIENT, ProviderFailures.classify(e).kind());
  }

  @Test
  void aSlowTagsResponseFailsWithinTheReadTimeout() {
    // Review Focus 3: 느린 Ollama 가 탐색 스케줄러를 붙들지 않는다.
    server.enqueue(tags("qwen2.5:7b").setBodyDelay(3, TimeUnit.SECONDS));
    OllamaProviderProbe p = new OllamaProviderProbe(
        "review", server.url("/").toString(), "qwen2.5:7b",
        Duration.ofSeconds(1), Duration.ofMillis(500));

    long started = System.nanoTime();
    RuntimeException e = assertThrows(RuntimeException.class, p::ping);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertEquals(FailureKind.TRANSIENT, ProviderFailures.classify(e).kind());
    assertTrue(elapsedMs < 2_500, "probe took " + elapsedMs + " ms");
  }
}
