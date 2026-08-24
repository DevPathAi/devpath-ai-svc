package ai.devpath.aigw.review;

import ai.devpath.aigw.release.ReleaseJourneyRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Short database transactions around a provider call that always runs outside a transaction. */
@Service
public class ReviewPersistenceService {

  private final AiCodeReviewRepository reviews;
  private final JsonMapper jsonMapper;
  private final JdbcTemplate jdbc;

  public ReviewPersistenceService(
      AiCodeReviewRepository reviews, JsonMapper jsonMapper, JdbcTemplate jdbc) {
    this.reviews = reviews;
    this.jsonMapper = jsonMapper;
    this.jdbc = jdbc;
  }

  /**
   * Compatibility entry point for direct callers. INSERT ON CONFLICT avoids poisoning the
   * transaction on a concurrent unique-key race before the requery.
   */
  @Transactional
  public AiCodeReview findOrCreatePending(long sandboxSessionId, long userId, Long contentId) {
    jdbc.update("INSERT INTO ai_code_reviews(sandbox_session_id,user_id,content_id,status) "
            + "VALUES (?,?,?,'PENDING') ON CONFLICT(sandbox_session_id) DO NOTHING",
        sandboxSessionId, userId, contentId);
    return reviews.findBySandboxSessionId(sandboxSessionId).orElseThrow();
  }

  /**
   * Records the at-least-once delivery and atomically acquires the session review when it is
   * pending or its previous PROCESSING lease has expired. No event payload is persisted.
   */
  @Transactional
  public Optional<ReviewClaim> claim(
      UUID eventId, long sandboxSessionId, long userId, Long contentId, Duration leaseDuration) {
    if (eventId == null) throw new IllegalArgumentException("eventId is required");
    if (leaseDuration.isNegative()) throw new IllegalArgumentException("leaseDuration must be >= 0");

    List<Long> correlatedSessions = jdbc.query(
        "INSERT INTO ai_review_event_inbox(event_id,sandbox_session_id) VALUES (?,?) "
            + "ON CONFLICT(event_id) DO UPDATE SET "
            + "last_received_at=CURRENT_TIMESTAMP, "
            + "delivery_count=ai_review_event_inbox.delivery_count+1 "
            + "WHERE ai_review_event_inbox.sandbox_session_id=EXCLUDED.sandbox_session_id "
            + "RETURNING sandbox_session_id",
        (rs, rowNum) -> rs.getLong(1), eventId, sandboxSessionId);
    if (correlatedSessions.isEmpty()) {
      return Optional.empty(); // an eventId can never be rebound to another sandbox session
    }

    jdbc.update("INSERT INTO ai_code_reviews("
            + "sandbox_session_id,user_id,content_id,status,source_event_id) "
            + "VALUES (?,?,?,'PENDING',?) ON CONFLICT(sandbox_session_id) DO NOTHING",
        sandboxSessionId, userId, contentId, eventId);
    jdbc.update("UPDATE ai_code_reviews SET "
            + "content_id=COALESCE(content_id,?), source_event_id=COALESCE(source_event_id,?) "
            + "WHERE sandbox_session_id=? AND user_id=?",
        contentId, eventId, sandboxSessionId, userId);

    UUID processingToken = UUID.randomUUID();
    Instant leaseExpiresAt = Instant.now().plus(leaseDuration);
    List<Long> claimed = jdbc.query(
        "UPDATE ai_code_reviews SET status='PROCESSING', processing_token=?, "
            + "lease_expires_at=?, error_code=NULL "
            + "WHERE sandbox_session_id=? AND user_id=? AND (status='PENDING' OR "
            + "(status='PROCESSING' AND lease_expires_at<=CURRENT_TIMESTAMP)) RETURNING id",
        (rs, rowNum) -> rs.getLong(1),
        processingToken, Timestamp.from(leaseExpiresAt), sandboxSessionId, userId);
    if (claimed.isEmpty()) {
      markInboxProcessedWhenReviewIsTerminal(eventId);
      return Optional.empty();
    }
    return Optional.of(new ReviewClaim(
        claimed.getFirst(), sandboxSessionId, eventId, processingToken));
  }

  /**
   * Classifies a failed claim only when the inbox event, session, owner, and original provider
   * effect all match. An unrelated event must never inherit another review's retrying lease.
   */
  @Transactional(readOnly = true)
  public ReviewDisposition dispositionForDeniedClaim(
      UUID eventId, long sandboxSessionId, long userId) {
    List<String> states = jdbc.query(
        "SELECT review.status FROM ai_review_event_inbox inbox "
            + "JOIN ai_code_reviews review "
            + "ON review.sandbox_session_id=inbox.sandbox_session_id "
            + "WHERE inbox.event_id=? AND inbox.sandbox_session_id=? "
            + "AND review.user_id=? AND review.source_event_id=?",
        (rs, rowNum) -> rs.getString(1), eventId, sandboxSessionId, userId, eventId);
    if (states.isEmpty()) return ReviewDisposition.REJECTED;
    return switch (states.getFirst()) {
      case "PENDING", "PROCESSING" -> ReviewDisposition.IN_PROGRESS;
      case "DONE", "FAILED" -> ReviewDisposition.TERMINAL_DUPLICATE;
      default -> ReviewDisposition.REJECTED;
    };
  }

  /** Release only the current fenced worker so Kafka may retry a transient provider failure. */
  @Transactional
  public boolean releaseForRetry(ReviewClaim claim) {
    return jdbc.update("UPDATE ai_code_reviews SET status='PENDING', processing_token=NULL, "
            + "lease_expires_at=NULL WHERE id=? AND status='PROCESSING' AND processing_token=?",
        claim.reviewId(), claim.processingToken()) == 1;
  }

  /** Kafka retry exhaustion may terminate PENDING only; it cannot overwrite an active/terminal row. */
  @Transactional
  public void markExhausted(long sandboxSessionId) {
    int changed = jdbc.update("UPDATE ai_code_reviews SET status='FAILED', "
            + "error_code='LLM_EXHAUSTED' WHERE sandbox_session_id=? AND status='PENDING'",
        sandboxSessionId);
    if (changed == 1) markSessionInboxProcessed(sandboxSessionId);
  }

  /** Conditional terminal transition fenced by the exact processing token. */
  @Transactional
  public boolean finishDone(ReviewClaim claim, ReviewResult result, String provider) {
    int changed = jdbc.update("UPDATE ai_code_reviews SET status='DONE', provider=?, confidence=?, "
            + "strengths=CAST(? AS jsonb), improvements=CAST(? AS jsonb), "
            + "security=CAST(? AS jsonb), error_code=NULL, processing_token=NULL, "
            + "lease_expires_at=NULL WHERE id=? AND status='PROCESSING' AND processing_token=?",
        provider,
        Math.max(0, Math.min(100, result.confidence())),
        toJson(result.strengths()), toJson(result.improvements()), toJson(result.security()),
        claim.reviewId(), claim.processingToken());
    if (changed == 1) markSessionInboxProcessed(claim.sandboxSessionId());
    return changed == 1;
  }

  /** Conditional terminal transition fenced by the exact processing token. */
  @Transactional
  public boolean finishFailed(ReviewClaim claim, String errorCode) {
    int changed = jdbc.update("UPDATE ai_code_reviews SET status='FAILED', error_code=?, "
            + "processing_token=NULL, lease_expires_at=NULL "
            + "WHERE id=? AND status='PROCESSING' AND processing_token=?",
        errorCode, claim.reviewId(), claim.processingToken());
    if (changed == 1) markSessionInboxProcessed(claim.sandboxSessionId());
    return changed == 1;
  }

  /** Staging release fault: externally visible FAILED while its Kafka delivery stays unprocessed. */
  @Transactional
  public boolean finishReleaseFailed(ReviewClaim claim) {
    return jdbc.update("UPDATE ai_code_reviews SET status='FAILED', "
            + "error_code='RELEASE_INJECTED_REVIEW', processing_token=NULL, "
            + "lease_expires_at=NULL WHERE id=? AND status='PROCESSING' AND processing_token=?",
        claim.reviewId(), claim.processingToken()) == 1;
  }

  /** Reopens only the exact synthetic failure so the held Kafka delivery performs the retry. */
  @Transactional
  public boolean reopenReleaseFailure(ReleaseJourneyRegistry.ReviewReplay replay) {
    return jdbc.update("UPDATE ai_code_reviews SET status='PENDING', error_code=NULL, "
            + "processing_token=NULL, lease_expires_at=NULL "
            + "WHERE sandbox_session_id=? AND user_id=? AND source_event_id=? "
            + "AND status='FAILED' AND error_code='RELEASE_INJECTED_REVIEW'",
        replay.sessionId(), replay.userId(), replay.eventId()) == 1;
  }

  /** Legacy test/support transition: PENDING only, never an active or terminal review. */
  @Transactional
  public void finishDone(long reviewId, ReviewResult result, String provider) {
    jdbc.update("UPDATE ai_code_reviews SET status='DONE', provider=?, confidence=?, "
            + "strengths=CAST(? AS jsonb), improvements=CAST(? AS jsonb), "
            + "security=CAST(? AS jsonb), error_code=NULL "
            + "WHERE id=? AND status='PENDING'",
        provider,
        Math.max(0, Math.min(100, result.confidence())),
        toJson(result.strengths()), toJson(result.improvements()), toJson(result.security()),
        reviewId);
  }

  /** Legacy test/support transition: PENDING only, never an active or terminal review. */
  @Transactional
  public void finishFailed(long reviewId, String errorCode) {
    jdbc.update("UPDATE ai_code_reviews SET status='FAILED', error_code=? "
        + "WHERE id=? AND status='PENDING'", errorCode, reviewId);
  }

  private void markInboxProcessedWhenReviewIsTerminal(UUID eventId) {
    jdbc.update("UPDATE ai_review_event_inbox inbox SET processed_at=CURRENT_TIMESTAMP "
            + "WHERE inbox.event_id=? AND inbox.processed_at IS NULL AND EXISTS ("
            + "SELECT 1 FROM ai_code_reviews review "
            + "WHERE review.sandbox_session_id=inbox.sandbox_session_id "
            + "AND review.status IN ('DONE','FAILED'))",
        eventId);
  }

  private void markSessionInboxProcessed(long sandboxSessionId) {
    jdbc.update("UPDATE ai_review_event_inbox SET processed_at=CURRENT_TIMESTAMP "
            + "WHERE sandbox_session_id=? AND processed_at IS NULL",
        sandboxSessionId);
  }

  private String toJson(List<?> value) {
    try {
      return jsonMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("review result serialization failed", e);
    }
  }
}
