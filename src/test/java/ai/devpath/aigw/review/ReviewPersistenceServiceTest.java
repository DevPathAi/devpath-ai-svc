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
    AiCodeReview pending = persistence.createPendingIfAbsent(sid, 5L, null).orElseThrow();

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
    AiCodeReview pending = persistence.createPendingIfAbsent(sid, 6L, null).orElseThrow();

    persistence.finishDone(pending.getId(),
        new ReviewResult(-5, List.of(), List.of(), List.of()), "MOCK");

    AiCodeReview done = reviews.findById(pending.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo("DONE");
    assertThat(done.getConfidence()).isEqualTo(0);
    reviews.delete(done);
  }
}
