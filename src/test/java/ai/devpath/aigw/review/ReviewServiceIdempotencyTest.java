package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ReviewServiceIdempotencyTest {

  @Autowired ReviewService service;
  @Autowired ReviewPersistenceService persistence;
  @Autowired AiCodeReviewRepository reviews;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean SandboxClient sandboxClient;
  @MockitoBean AiReviewClient aiReviewClient;

  private long sid;

  @AfterEach
  void cleanUp() {
    if (sid != 0) {
      jdbc.update("DELETE FROM ai_review_event_inbox WHERE sandbox_session_id=?", sid);
      jdbc.update("DELETE FROM ai_code_reviews WHERE sandbox_session_id=?", sid);
    }
  }

  @Test
  void sameEventDeliveredSequentiallyInvokesProviderExactlyOnce() {
    sid = positiveId();
    stubSuccessfulProvider();
    UUID eventId = UUID.randomUUID();

    assertThat(service.reviewRun(eventId, sid, 42L, 3L))
        .isEqualTo(ReviewDisposition.COMPLETED);
    assertThat(service.reviewRun(eventId, sid, 42L, 3L))
        .isEqualTo(ReviewDisposition.TERMINAL_DUPLICATE);

    verify(aiReviewClient, times(1)).review(any());
    assertThat(reviews.findBySandboxSessionId(sid).orElseThrow().getStatus()).isEqualTo("DONE");
  }

  @Test
  void concurrentDuplicateDuringProviderCallInvokesProviderExactlyOnce() throws Exception {
    sid = positiveId();
    UUID eventId = UUID.randomUUID();
    CountDownLatch enteredProvider = new CountDownLatch(1);
    CountDownLatch releaseProvider = new CountDownLatch(1);
    when(sandboxClient.getSession(anyLong())).thenReturn(session());
    when(aiReviewClient.providerName()).thenReturn("MOCK");
    when(aiReviewClient.review(any())).thenAnswer(invocation -> {
      enteredProvider.countDown();
      assertThat(releaseProvider.await(10, TimeUnit.SECONDS)).isTrue();
      return result();
    });

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> service.reviewRun(eventId, sid, 42L, 3L));
      assertThat(enteredProvider.await(10, TimeUnit.SECONDS)).isTrue();
      var duplicate = executor.submit(() -> service.reviewRun(eventId, sid, 42L, 3L));
      assertThat(duplicate.get(10, TimeUnit.SECONDS)).isEqualTo(ReviewDisposition.IN_PROGRESS);
      releaseProvider.countDown();
      first.get(10, TimeUnit.SECONDS);
    }

    verify(aiReviewClient, times(1)).review(any());
    assertThat(reviews.findBySandboxSessionId(sid).orElseThrow().getStatus()).isEqualTo("DONE");
  }

  @Test
  void crashedClaimBeforeProviderCanBeRecoveredAfterLeaseExpiryWithOneProviderEffect() {
    sid = positiveId();
    UUID eventId = UUID.randomUUID();
    persistence.claim(eventId, sid, 42L, 3L, Duration.ZERO).orElseThrow();
    stubSuccessfulProvider();

    service.reviewRun(eventId, sid, 42L, 3L);

    verify(aiReviewClient, times(1)).review(any());
    assertThat(reviews.findBySandboxSessionId(sid).orElseThrow().getStatus()).isEqualTo("DONE");
  }

  private void stubSuccessfulProvider() {
    when(sandboxClient.getSession(anyLong())).thenReturn(session());
    when(aiReviewClient.providerName()).thenReturn("MOCK");
    when(aiReviewClient.review(any())).thenReturn(result());
  }

  private SandboxSessionView session() {
    return new SandboxSessionView(
        sid, 42L, "PYTHON", 3L, "print(1)", "1\n", "", 0, "COMPLETED");
  }

  private static ReviewResult result() {
    return new ReviewResult(90, List.of("clear"), List.of(), List.of());
  }

  private static long positiveId() {
    return System.nanoTime() & Long.MAX_VALUE;
  }
}
