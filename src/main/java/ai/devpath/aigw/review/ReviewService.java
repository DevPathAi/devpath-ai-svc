package ai.devpath.aigw.review;

import ai.devpath.aigw.release.ReleaseJourneyRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Review orchestration with a short database claim, provider call outside a transaction, and a
 * fenced terminal update. The lease prevents duplicate/rolling consumers from sharing ownership.
 */
@Service
public class ReviewService {

  private final ReviewPersistenceService persistence;
  private final SandboxClient sandboxClient;
  private final AiReviewClient aiReviewClient;
  private final Duration processingLease;
  private final ReleaseJourneyRegistry release;

  @Autowired
  public ReviewService(
      ReviewPersistenceService persistence,
      SandboxClient sandboxClient,
      AiReviewClient aiReviewClient,
      @Value("${devpath.review.processing-lease:PT5M}") Duration processingLease,
      ReleaseJourneyRegistry release) {
    this.persistence = persistence;
    this.sandboxClient = sandboxClient;
    this.aiReviewClient = aiReviewClient;
    this.release = release;
    if (processingLease.isZero() || processingLease.isNegative()) {
      throw new IllegalArgumentException("review processing lease must be positive");
    }
    this.processingLease = processingLease;
  }

  ReviewService(
      ReviewPersistenceService persistence,
      SandboxClient sandboxClient,
      AiReviewClient aiReviewClient,
      Duration processingLease) {
    this(persistence, sandboxClient, aiReviewClient, processingLease, null);
  }

  /** Compatibility for direct legacy callers that did not carry an outbox eventId. */
  public ReviewDisposition reviewRun(long sandboxSessionId, long userId, Long contentId) {
    return reviewRun(
        ReviewEventIdentity.legacy(sandboxSessionId), sandboxSessionId, userId, contentId);
  }

  public ReviewDisposition reviewRun(
      UUID eventId, long sandboxSessionId, long userId, Long contentId) {
    var maybeClaim = persistence.claim(
        eventId, sandboxSessionId, userId, contentId, processingLease);
    if (maybeClaim.isEmpty()) {
      return persistence.dispositionForDeniedClaim(eventId, sandboxSessionId, userId);
    }

    ReviewClaim claim = maybeClaim.get();
    SandboxSessionView session;
    try {
      session = sandboxClient.getSession(sandboxSessionId);
    } catch (SandboxUnavailableException e) {
      return terminalDisposition(
          persistence.finishFailed(claim, "SANDBOX_UNAVAILABLE"), claim, userId);
    }
    if (session.userId() == null || session.userId() != userId) {
      return terminalDisposition(
          persistence.finishFailed(claim, "OWNERSHIP_MISMATCH"), claim, userId);
    }

    if (release != null) {
      var releaseFault = release.consumeReview(
          userId, eventId, sandboxSessionId, contentId);
      if (releaseFault.isPresent()) {
        if (persistence.finishReleaseFailed(claim)) {
          release.recordReviewFailure(releaseFault.get());
          return ReviewDisposition.IN_PROGRESS;
        }
        return persistence.dispositionForDeniedClaim(
            claim.eventId(), claim.sandboxSessionId(), userId);
      }
    }

    ReviewResult result;
    String provider;
    try {
      provider = aiReviewClient.providerName();
      result = aiReviewClient.review(new ReviewInput(
          session.language(), session.submittedCode(),
          session.stdout(), session.stderr(), session.exitCode()));
    } catch (TransientReviewException e) {
      if (persistence.releaseForRetry(claim)) throw e;
      return persistence.dispositionForDeniedClaim(
          claim.eventId(), claim.sandboxSessionId(), userId);
    } catch (PermanentReviewException e) {
      return terminalDisposition(
          persistence.finishFailed(claim, e.errorCode()), claim, userId);
    } catch (RuntimeException e) {
      return terminalDisposition(
          persistence.finishFailed(claim, "LLM_FAILED"), claim, userId);
    }

    // Persistence is intentionally outside the provider exception boundary. If this write fails,
    // Kafka retry/lease recovery must handle it; the successful provider effect is not LLM_FAILED.
    if (persistence.finishDone(claim, result, provider)) {
      if (release != null) {
        release.recordReviewCompleted(eventId, sandboxSessionId, userId);
      }
      return ReviewDisposition.COMPLETED;
    }
    return persistence.dispositionForDeniedClaim(
        claim.eventId(), claim.sandboxSessionId(), userId);
  }

  private ReviewDisposition terminalDisposition(
      boolean finished, ReviewClaim claim, long userId) {
    return finished
        ? ReviewDisposition.COMPLETED
        : persistence.dispositionForDeniedClaim(
            claim.eventId(), claim.sandboxSessionId(), userId);
  }
}
