package ai.devpath.aigw.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

class OllamaReEngagementClientTest {

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

	/** 실제 ReEngagementPromptBuilder 를 쓰므로 null 이 아닌 입력이 필요하다. */
	private static final ReEngagementInput INPUT = new ReEngagementInput(
			7L, Instant.parse("2026-09-20T00:00:00Z"), 11, "Spring Boot 기초 3/12주");

	private OllamaReEngagementClient client() {
		return new OllamaReEngagementClient(
				server.url("/").toString(), "qwen2.5:7b", Duration.ofSeconds(5),
				new ReEngagementPromptBuilder());
	}

	@Test
	void postsToTheChatRouteWithStreamingOff() throws Exception {
		server.enqueue(new MockResponse()
				.setHeader("Content-Type", "application/json")
				.setBody("{\"message\":{\"content\":\"다시 시작해 볼까요?\"}}"));

		String out = client().suggest(INPUT);

		RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/chat", request.getPath());
		String body = request.getBody().readUtf8();
		assertTrue(body.contains("\"stream\":false"), body);
		assertTrue(body.contains("qwen2.5:7b"), body);
		assertEquals("다시 시작해 볼까요?", out);
	}

	@Test
	void trimsTheAnswer() {
		server.enqueue(new MockResponse()
				.setHeader("Content-Type", "application/json")
				.setBody("{\"message\":{\"content\":\"  문구  \"}}"));

		assertEquals("문구", client().suggest(INPUT));
	}

	@Test
	void reportsAnEmptyAnswerAsAGenerationFailure() {
		server.enqueue(new MockResponse()
				.setHeader("Content-Type", "application/json")
				.setBody("{\"message\":{\"content\":\"   \"}}"));

		assertThrows(ReEngagementGenerationException.class, () -> client().suggest(INPUT));
	}

	@Test
	void letsAnHttpStatusFailureThroughSoTheLatchCanClassifyIt() {
		// 상태코드를 ReEngagementGenerationException 으로 감싸면 ProviderFailures 가 429 를 못 본다.
		server.enqueue(new MockResponse().setResponseCode(429));

		assertThrows(RestClientResponseException.class, () -> client().suggest(INPUT));
	}

	@Test
	void namesItselfOllama() {
		assertEquals("OLLAMA", client().providerName());
	}
}
