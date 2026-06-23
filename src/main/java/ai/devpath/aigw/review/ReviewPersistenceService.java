package ai.devpath.aigw.review;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 리뷰 영속을 짧은 @Transactional로 분리(LLM 호출은 ReviewService에서 tx 밖). */
@Service
public class ReviewPersistenceService {

  private final AiCodeReviewRepository reviews;
  private final JsonMapper jsonMapper;

  public ReviewPersistenceService(AiCodeReviewRepository reviews, JsonMapper jsonMapper) {
    this.reviews = reviews;
    this.jsonMapper = jsonMapper;
  }

  /** 멱등: 이미 있으면 empty. UNIQUE(sandbox_session_id) 경합도 empty로 흡수. */
  @Transactional
  public Optional<AiCodeReview> createPendingIfAbsent(long sandboxSessionId, long userId, Long contentId) {
    if (reviews.existsBySandboxSessionId(sandboxSessionId)) {
      return Optional.empty();
    }
    AiCodeReview r = new AiCodeReview();
    r.setSandboxSessionId(sandboxSessionId);
    r.setUserId(userId);
    r.setContentId(contentId);
    r.setStatus("PENDING");
    try {
      return Optional.of(reviews.saveAndFlush(r));
    } catch (DataIntegrityViolationException dup) {
      return Optional.empty();
    }
  }

  @Transactional
  public void finishDone(long reviewId, ReviewResult result, String provider) {
    AiCodeReview r = reviews.findById(reviewId).orElseThrow();
    r.setStatus("DONE");
    r.setProvider(provider);
    r.setConfidence(result.confidence());
    r.setStrengths(toJson(result.strengths()));
    r.setImprovements(toJson(result.improvements()));
    r.setSecurity(toJson(result.security()));
    reviews.save(r);
  }

  @Transactional
  public void finishFailed(long reviewId, String errorCode) {
    AiCodeReview r = reviews.findById(reviewId).orElseThrow();
    r.setStatus("FAILED");
    r.setErrorCode(errorCode);
    reviews.save(r);
  }

  private String toJson(List<?> value) {
    try {
      return jsonMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("리뷰 결과 직렬화 실패", e);
    }
  }
}
