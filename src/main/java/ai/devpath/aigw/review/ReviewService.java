package ai.devpath.aigw.review;

import org.springframework.stereotype.Service;

/**
 * 리뷰 오케스트레이션. PENDING 선삽입(짧은 tx) → 세션 조회 + LLM(트랜잭션 밖) → DONE/FAILED(짧은 tx).
 * 자기호출 프록시 우회를 피하려 tx 메서드는 ReviewPersistenceService에 둔다(슬라이스 #3/#5 패턴).
 */
@Service
public class ReviewService {

  private final ReviewPersistenceService persistence;
  private final SandboxClient sandboxClient;
  private final AiReviewClient aiReviewClient;

  public ReviewService(ReviewPersistenceService persistence, SandboxClient sandboxClient,
      AiReviewClient aiReviewClient) {
    this.persistence = persistence;
    this.sandboxClient = sandboxClient;
    this.aiReviewClient = aiReviewClient;
  }

  public void reviewRun(long sandboxSessionId, long userId, Long contentId) {
    AiCodeReview review = persistence.findOrCreatePending(sandboxSessionId, userId, contentId);
    if ("DONE".equals(review.getStatus()) || "FAILED".equals(review.getStatus())) {
      return; // 터미널 → 멱등 skip
    }
    long reviewId = review.getId();
    try {
      SandboxSessionView session = sandboxClient.getSession(sandboxSessionId);
      if (session.userId() == null || session.userId() != userId) {
        persistence.finishFailed(reviewId, "OWNERSHIP_MISMATCH");
        return;
      }
      ReviewResult result = aiReviewClient.review(new ReviewInput(
          session.language(), session.submittedCode(),
          session.stdout(), session.stderr(), session.exitCode()));
      persistence.finishDone(reviewId, result, aiReviewClient.providerName());
    } catch (TransientReviewException e) {
      throw e; // PENDING 유지 → Kafka 재시도
    } catch (PermanentReviewException e) {
      persistence.finishFailed(reviewId, e.errorCode());
    } catch (SandboxUnavailableException e) {
      persistence.finishFailed(reviewId, "SANDBOX_UNAVAILABLE");
    } catch (RuntimeException e) {
      persistence.finishFailed(reviewId, "LLM_FAILED"); // 예상밖 → 터미널(재시도 안 함)
    }
  }
}
