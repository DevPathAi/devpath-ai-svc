package ai.devpath.aigw.review;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(topics = "sandbox.run.submitted", partitions = 1)
class ReviewConsumerIT {

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper jsonMapper;
  @Autowired AiCodeReviewRepository reviews;
  @Autowired ReviewPersistenceService persistence;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean SandboxClient sandboxClient;
  @MockitoBean AiReviewClient aiReviewClient;

  private long sidUnderTest;

  @AfterEach
  void cleanUp() {
    if (sidUnderTest != 0) {
      jdbc.update("DELETE FROM ai_review_event_inbox WHERE sandbox_session_id=?", sidUnderTest);
      jdbc.update("DELETE FROM ai_code_reviews WHERE sandbox_session_id=?", sidUnderTest);
    }
  }

  @Test
  void consumesEventAndPersistsReview() throws Exception {
    long sid = System.nanoTime();
    sidUnderTest = sid;
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 42L, "PYTHON", null, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(aiReviewClient.providerName()).thenReturn("MOCK");
    when(aiReviewClient.review(any())).thenReturn(success());
    var event = new SandboxRunSubmittedEvent(UUID.randomUUID(), Instant.now(), 42L, sid, "PYTHON", null);

    kafka.send("sandbox.run.submitted", jsonMapper.writeValueAsString(event));

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
        org.assertj.core.api.Assertions.assertThat(reviews.findBySandboxSessionId(sid))
            .get().extracting(AiCodeReview::getStatus).isEqualTo("DONE"));
    verify(aiReviewClient, times(1)).review(any());
  }

  @Test
  void ackGapRedeliveryWithSameEventIdInvokesProviderExactlyOnce() throws Exception {
    long sid = System.nanoTime() & Long.MAX_VALUE;
    sidUnderTest = sid;
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 42L, "PYTHON", null, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(aiReviewClient.providerName()).thenReturn("MOCK");
    when(aiReviewClient.review(any())).thenReturn(success());
    var event = new SandboxRunSubmittedEvent(
        UUID.randomUUID(), Instant.now(), 42L, sid, "PYTHON", null);
    String payload = jsonMapper.writeValueAsString(event);

    kafka.send("sandbox.run.submitted", Long.toString(sid), payload);
    kafka.send("sandbox.run.submitted", Long.toString(sid), payload);

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      org.assertj.core.api.Assertions.assertThat(reviews.findBySandboxSessionId(sid))
          .get().extracting(AiCodeReview::getStatus).isEqualTo("DONE");
      verify(aiReviewClient, times(1)).review(any());
    });
  }

  @Test
  void legacyPayloadWithoutEventMetadataIsDeterministicallyDeduplicated() throws Exception {
    long sid = System.nanoTime() & Long.MAX_VALUE;
    sidUnderTest = sid;
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 42L, "PYTHON", null, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(aiReviewClient.providerName()).thenReturn("MOCK");
    when(aiReviewClient.review(any())).thenReturn(success());
    String legacyPayload = "{\"userId\":42,\"sandboxSessionId\":" + sid
        + ",\"language\":\"PYTHON\",\"contentId\":null}";

    kafka.send("sandbox.run.submitted", Long.toString(sid), legacyPayload);
    kafka.send("sandbox.run.submitted", Long.toString(sid), legacyPayload);

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      org.assertj.core.api.Assertions.assertThat(reviews.findBySandboxSessionId(sid))
          .get().extracting(AiCodeReview::getStatus).isEqualTo("DONE");
      verify(aiReviewClient, times(1)).review(any());
    });
  }

  @Test
  void crashedActiveClaimKeepsRecordRetryingUntilLeaseExpires() throws Exception {
    long sid = System.nanoTime() & Long.MAX_VALUE;
    sidUnderTest = sid;
    UUID eventId = UUID.randomUUID();
    persistence.claim(eventId, sid, 42L, null, Duration.ofMillis(500)).orElseThrow();
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 42L, "PYTHON", null, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(aiReviewClient.providerName()).thenReturn("MOCK");
    when(aiReviewClient.review(any())).thenReturn(success());
    var event = new SandboxRunSubmittedEvent(
        eventId, Instant.now(), 42L, sid, "PYTHON", null);

    kafka.send("sandbox.run.submitted", Long.toString(sid), jsonMapper.writeValueAsString(event));

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      org.assertj.core.api.Assertions.assertThat(reviews.findBySandboxSessionId(sid))
          .get().extracting(AiCodeReview::getStatus).isEqualTo("DONE");
      verify(aiReviewClient, times(1)).review(any());
    });
  }

  private static ReviewResult success() {
    return new ReviewResult(90, List.of("clear"), List.of(), List.of());
  }
}
