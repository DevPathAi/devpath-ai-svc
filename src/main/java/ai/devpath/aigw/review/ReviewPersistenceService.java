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
    // LLM이 스키마(integer)만 지키고 범위(0~100)를 벗어난 값을 줄 수 있다. DB CHECK(chk_ai_review_confidence)
    // 위반으로 정상 리뷰 본문이 통째로 FAILED 되는 것을 막기 위해 [0,100]으로 클램프한다.
    r.setConfidence(Math.max(0, Math.min(100, result.confidence())));
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
