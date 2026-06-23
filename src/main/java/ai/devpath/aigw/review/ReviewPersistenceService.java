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

  /** 기존 행이 있으면 그대로 반환, 없으면 PENDING 신규 생성. never null. UNIQUE 경합은 재조회로 흡수. */
  @Transactional
  public AiCodeReview findOrCreatePending(long sandboxSessionId, long userId, Long contentId) {
    Optional<AiCodeReview> existing = reviews.findBySandboxSessionId(sandboxSessionId);
    if (existing.isPresent()) {
      return existing.get();
    }
    AiCodeReview r = new AiCodeReview();
    r.setSandboxSessionId(sandboxSessionId);
    r.setUserId(userId);
    r.setContentId(contentId);
    r.setStatus("PENDING");
    try {
      return reviews.saveAndFlush(r);
    } catch (DataIntegrityViolationException dup) {
      return reviews.findBySandboxSessionId(sandboxSessionId).orElseThrow();
    }
  }

  /** 재시도 소진 시 PENDING 행을 FAILED(LLM_EXHAUSTED)로 종료. 이미 터미널이면 무변경(멱등). */
  @Transactional
  public void markExhausted(long sandboxSessionId) {
    reviews.findBySandboxSessionId(sandboxSessionId).ifPresent(r -> {
      if ("PENDING".equals(r.getStatus())) {
        r.setStatus("FAILED");
        r.setErrorCode("LLM_EXHAUSTED");
        reviews.save(r);
      }
    });
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
