package ai.devpath.aigw.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.aigw.provider.FailureKind;
import ai.devpath.aigw.provider.ProviderLatch;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class FallbackReEngagementClientTest {

	private static final ReEngagementInput INPUT = new ReEngagementInput(
			7L, Instant.parse("2026-09-20T00:00:00Z"), 11, "Spring Boot 기초 3/12주");

	private static final class Stub implements ReEngagementSuggestionClient {
		private final String name;
		private final RuntimeException failure;
		private final String text;
		int calls;

		Stub(String name, RuntimeException failure, String text) {
			this.name = name;
			this.failure = failure;
			this.text = text;
		}

		@Override public String suggest(ReEngagementInput input) {
			calls++;
			if (failure != null) throw failure;
			return text;
		}

		@Override public String providerName() { return name; }
	}

	private static RestClientResponseException status(int code, String reason) {
		return new RestClientResponseException(reason, code, reason, new HttpHeaders(), null, null);
	}

	private ProviderLatch latch() {
		return new ProviderLatch(
				Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
	}

	private FallbackReEngagementClient chain(ProviderLatch latch, Stub... stubs) {
		LinkedHashMap<String, ReEngagementSuggestionClient> delegates = new LinkedHashMap<>();
		for (Stub s : stubs) delegates.put(s.providerName().toLowerCase(Locale.ROOT), s);
		return new FallbackReEngagementClient(delegates, latch);
	}

	@Test
	void movesToTheNextProviderWhenThePrimaryFails() {
		ProviderLatch latch = latch();
		Stub claude = new Stub("CLAUDE", status(429, "Too Many Requests"), null);

		assertEquals("from ollama",
				chain(latch, claude, new Stub("OLLAMA", null, "from ollama")).suggest(INPUT));
		assertEquals(1, claude.calls);
		assertTrue(latch.isOpen("retention", "claude"));
	}

	@Test
	void skipsAProviderWhoseLatchIsOpenWithoutCallingIt() {
		ProviderLatch latch = latch();
		latch.recordFailure("retention", "claude", FailureKind.AUTH, null);
		Stub claude = new Stub("CLAUDE", null, "never");

		assertEquals("from ollama",
				chain(latch, claude, new Stub("OLLAMA", null, "from ollama")).suggest(INPUT));
		assertEquals(0, claude.calls);
	}

	@Test
	void doesNotOpenTheLatchOnAnOutputFailure() {
		ProviderLatch latch = latch();

		assertThrows(RuntimeException.class,
				() -> chain(latch,
						new Stub("CLAUDE", new ReEngagementGenerationException("blank", null), null))
						.suggest(INPUT));

		assertFalse(latch.isOpen("retention", "claude"));
	}

	@Test
	void reportsTheProviderThatActuallyServed() {
		ProviderLatch latch = latch();
		FallbackReEngagementClient client = chain(latch,
				new Stub("CLAUDE", status(429, "Too Many Requests"), null),
				new Stub("OLLAMA", null, "ok"));

		client.suggest(INPUT);

		assertEquals("OLLAMA", client.providerName());
	}

	private FallbackReEngagementClient chain(
			ProviderLatch latch, Map<String, ReEngagementSuggestionClient> lastResort, Stub... stubs) {
		LinkedHashMap<String, ReEngagementSuggestionClient> delegates = new LinkedHashMap<>();
		for (Stub s : stubs) delegates.put(s.providerName().toLowerCase(Locale.ROOT), s);
		return new FallbackReEngagementClient(delegates, lastResort, latch);
	}

	@Test
	void usesTheFastClaudeWhileAUsableFallbackFollows() {
		ProviderLatch latch = latch();
		Stub fast = new Stub("CLAUDE", status(503, "Service Unavailable"), null);
		Stub patient = new Stub("CLAUDE", null, "patient");

		assertEquals("ollama", chain(latch, Map.of("claude", patient), fast,
				new Stub("OLLAMA", null, "ollama")).suggest(INPUT));
		assertEquals(1, fast.calls);
		assertEquals(0, patient.calls);
	}

	@Test
	void usesTheLastResortClaudeWhenTheFallbackIsBlocked() {
		ProviderLatch latch = latch();
		latch.recordFailure("retention", "ollama", FailureKind.AUTH, null);
		Stub fast = new Stub("CLAUDE", null, "fast");
		Stub patient = new Stub("CLAUDE", null, "patient");

		assertEquals("patient", chain(latch, Map.of("claude", patient), fast,
				new Stub("OLLAMA", null, "ollama")).suggest(INPUT));
		assertEquals(0, fast.calls);
		assertEquals(1, patient.calls);
	}

	@Test
	void callsThePrimaryLastResortOnceWhenEveryProviderIsBlocked() {
		// 스펙 §3.1-1 규칙 ③(예전에는 ReEngagementGenerationException 으로 실패).
		ProviderLatch latch = latch();
		latch.recordFailure("retention", "claude", FailureKind.RATE_LIMIT, null);
		latch.recordFailure("retention", "ollama", FailureKind.AUTH, null);
		Stub patient = new Stub("CLAUDE", null, "patient");

		assertEquals("patient", chain(latch, Map.of("claude", patient),
				new Stub("CLAUDE", null, "fast"), new Stub("OLLAMA", null, "ollama")).suggest(INPUT));
		assertEquals(1, patient.calls);
	}

	@Test
	void surfacesTheLastResortFailureWhenEveryProviderIsBlocked() {
		ProviderLatch latch = latch();
		latch.recordFailure("retention", "claude", FailureKind.RATE_LIMIT, null);
		latch.recordFailure("retention", "ollama", FailureKind.AUTH, null);
		ReEngagementGenerationException failure =
				new ReEngagementGenerationException("Claude 재참여 문구 생성 실패", null);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> chain(latch, Map.of("claude", new Stub("CLAUDE", failure, null)),
						new Stub("CLAUDE", null, "fast"), new Stub("OLLAMA", null, "ollama")).suggest(INPUT));
		assertSame(failure, thrown);
	}

	@Test
	void reportsClaudeWhenTheLastResortVariantServed() {
		ProviderLatch latch = latch();
		latch.recordFailure("retention", "ollama", FailureKind.AUTH, null);
		FallbackReEngagementClient client = chain(latch,
				Map.of("claude", new Stub("CLAUDE", null, "patient")),
				new Stub("CLAUDE", null, "fast"), new Stub("OLLAMA", null, "ollama"));

		client.suggest(INPUT);

		assertEquals("CLAUDE", client.providerName());
	}
}
