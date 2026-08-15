package ai.devpath.aigw.review;

import java.time.Duration;
import java.util.UUID;
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

  public ReviewService(
      ReviewPersistenceService persistence,
      SandboxClient sandboxClient,
      AiReviewClient aiReviewClient,
      @Value("${devpath.review.processing-lease:PT5M}") Duration processingLease) {
    this.persistence = persistence;
    this.sandboxClient = sandboxClient;
    this.aiReviewClient = aiReviewClient;
    if (processingLease.isZero() || processingLease.isNegative()) {
      throw new IllegalArgumentException("review processing lease must be positive");
    }
    this.processingLease = processingLease;
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
    if (maybeClaim.isEmpty()) return currentDisposition(sandboxSessionId);

    ReviewClaim claim = maybeClaim.get();
    try {
      SandboxSessionView session = sandboxClient.getSession(sandboxSessionId);
      if (session.userId() == null || session.userId() != userId) {
        return terminalDisposition(
            persistence.finishFailed(claim, "OWNERSHIP_MISMATCH"), sandboxSessionId);
      }
      ReviewResult result = aiReviewClient.review(new ReviewInput(
          session.language(), session.submittedCode(),
          session.stdout(), session.stderr(), session.exitCode()));
      if (persistence.finishDone(claim, result, aiReviewClient.providerName())) {
        return ReviewDisposition.COMPLETED;
      }
    } catch (TransientReviewException e) {
      if (persistence.releaseForRetry(claim)) throw e;
    } catch (PermanentReviewException e) {
      return terminalDisposition(
          persistence.finishFailed(claim, e.errorCode()), sandboxSessionId);
    } catch (SandboxUnavailableException e) {
      return terminalDisposition(
          persistence.finishFailed(claim, "SANDBOX_UNAVAILABLE"), sandboxSessionId);
    } catch (RuntimeException e) {
      return terminalDisposition(
          persistence.finishFailed(claim, "LLM_FAILED"), sandboxSessionId);
    }
    return currentDisposition(sandboxSessionId);
  }

  private ReviewDisposition currentDisposition(long sandboxSessionId) {
    return persistence.isProcessing(sandboxSessionId)
        ? ReviewDisposition.IN_PROGRESS
        : ReviewDisposition.TERMINAL_DUPLICATE;
  }

  private ReviewDisposition terminalDisposition(boolean finished, long sandboxSessionId) {
    return finished ? ReviewDisposition.COMPLETED : currentDisposition(sandboxSessionId);
  }
}
