package ai.devpath.aigw.review;

import java.util.Optional;
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
    Optional<AiCodeReview> created = persistence.createPendingIfAbsent(sandboxSessionId, userId, contentId);
    if (created.isEmpty()) {
      return; // 멱등: 이미 리뷰 존재
    }
    long reviewId = created.get().getId();
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
    } catch (RuntimeException e) {
      persistence.finishFailed(reviewId, "LLM_FAILED");
    }
  }
}
