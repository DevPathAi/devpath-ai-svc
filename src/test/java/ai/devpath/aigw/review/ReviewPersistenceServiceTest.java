package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReviewPersistenceServiceTest {

  @Autowired ReviewPersistenceService persistence;
  @Autowired AiCodeReviewRepository reviews;

  @Test
  void aboveRangeConfidenceIsClampedTo100NotFailed() {
    long sid = System.nanoTime();
    AiCodeReview pending = persistence.findOrCreatePending(sid, 5L, null);

    // LLM이 범위 밖(151) confidence를 줘도 정상 리뷰가 폐기되지 않고 클램프되어 DONE.
    persistence.finishDone(pending.getId(),
        new ReviewResult(151, List.of("clear"), List.of(), List.of()), "MOCK");

    AiCodeReview done = reviews.findById(pending.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo("DONE");
    assertThat(done.getConfidence()).isEqualTo(100);
    reviews.delete(done);
  }

  @Test
  void belowRangeConfidenceIsClampedTo0() {
    long sid = System.nanoTime();
    AiCodeReview pending = persistence.findOrCreatePending(sid, 6L, null);

    persistence.finishDone(pending.getId(),
        new ReviewResult(-5, List.of(), List.of(), List.of()), "MOCK");

    AiCodeReview done = reviews.findById(pending.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo("DONE");
    assertThat(done.getConfidence()).isEqualTo(0);
    reviews.delete(done);
  }

  @Test
  void findOrCreatePendingReturnsExistingOnSecondCall() {
    long sid = System.nanoTime();
    AiCodeReview first = persistence.findOrCreatePending(sid, 5L, null);
    AiCodeReview second = persistence.findOrCreatePending(sid, 5L, null);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(reviews.findAll().stream()
        .filter(x -> x.getSandboxSessionId() == sid).count()).isEqualTo(1L);
    reviews.deleteById(first.getId());
  }

  @Test
  void markExhaustedSetsFailedLlmExhaustedOnPending() {
    long sid = System.nanoTime();
    AiCodeReview pending = persistence.findOrCreatePending(sid, 5L, null);

    persistence.markExhausted(sid);

    AiCodeReview after = reviews.findById(pending.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo("FAILED");
    assertThat(after.getErrorCode()).isEqualTo("LLM_EXHAUSTED");
    reviews.deleteById(pending.getId());
  }

  @Test
  void markExhaustedDoesNotOverwriteTerminal() {
    long sid = System.nanoTime();
    AiCodeReview pending = persistence.findOrCreatePending(sid, 5L, null);
    persistence.finishDone(pending.getId(),
        new ReviewResult(90, List.of("ok"), List.of(), List.of()), "MOCK");

    persistence.markExhausted(sid); // 이미 DONE → 무변경

    AiCodeReview after = reviews.findById(pending.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo("DONE");
    reviews.deleteById(pending.getId());
  }
}
