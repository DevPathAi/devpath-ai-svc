package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReviewLeasePersistenceTest {

  @Autowired ReviewPersistenceService persistence;
  @Autowired AiCodeReviewRepository reviews;
  @Autowired JdbcTemplate jdbc;

  private long sid;

  @AfterEach
  void cleanUp() {
    if (sid != 0) {
      jdbc.update("DELETE FROM ai_review_event_inbox WHERE sandbox_session_id=?", sid);
      jdbc.update("DELETE FROM ai_code_reviews WHERE sandbox_session_id=?", sid);
    }
  }

  @Test
  void concurrentCreateUsesConflictSafeInsertAndRequery() throws Exception {
    sid = positiveId();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var one = executor.submit(() -> {
        barrier.await();
        return persistence.findOrCreatePending(sid, 42L, 3L).getId();
      });
      var two = executor.submit(() -> {
        barrier.await();
        return persistence.findOrCreatePending(sid, 42L, 3L).getId();
      });

      assertThat(one.get()).isEqualTo(two.get());
      assertThat(reviews.findAll().stream()
          .filter(review -> review.getSandboxSessionId() == sid)).hasSize(1);
    }
  }

  @Test
  void expiredLeaseCanBeReclaimedAndStaleWorkerCannotOverwriteTerminalResult() {
    sid = positiveId();
    UUID firstEvent = UUID.randomUUID();
    UUID secondEvent = UUID.randomUUID();
    ReviewClaim first = persistence.claim(
        firstEvent, sid, 42L, 3L, Duration.ofMinutes(5)).orElseThrow();

    jdbc.update("UPDATE ai_code_reviews SET lease_expires_at=now()-interval '1 second' "
        + "WHERE id=?", first.reviewId());

    ReviewClaim second = persistence.claim(
        secondEvent, sid, 42L, 3L, Duration.ofMinutes(5)).orElseThrow();
    assertThat(second.processingToken()).isNotEqualTo(first.processingToken());

    assertThat(persistence.finishDone(second,
        new ReviewResult(91, List.of("new"), List.of(), List.of()), "MOCK")).isTrue();
    assertThat(persistence.finishFailed(first, "STALE_WORKER")).isFalse();

    AiCodeReview terminal = reviews.findById(second.reviewId()).orElseThrow();
    assertThat(terminal.getStatus()).isEqualTo("DONE");
    assertThat(terminal.getConfidence()).isEqualTo(91);
    assertThat(terminal.getErrorCode()).isNull();
    assertThat(terminal.getProcessingToken()).isNull();
    assertThat(terminal.getLeaseExpiresAt()).isNull();
  }

  @Test
  void inboxStoresOnlyEventMetadataAndCountsAckGapRedelivery() {
    sid = positiveId();
    UUID eventId = UUID.randomUUID();

    persistence.claim(eventId, sid, 42L, null, Duration.ofMinutes(5)).orElseThrow();
    assertThat(persistence.claim(eventId, sid, 42L, null, Duration.ofMinutes(5))).isEmpty();

    var row = jdbc.queryForMap(
        "SELECT sandbox_session_id,delivery_count FROM ai_review_event_inbox WHERE event_id=?",
        eventId);
    assertThat(row).containsEntry("sandbox_session_id", sid)
        .containsEntry("delivery_count", 2L);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='ai_review_event_inbox' "
            + "AND column_name IN ('payload','submitted_code','stdout','stderr')",
        Long.class)).isZero();
  }

  private static long positiveId() {
    return System.nanoTime() & Long.MAX_VALUE;
  }
}
