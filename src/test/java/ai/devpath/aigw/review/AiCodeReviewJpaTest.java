package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiCodeReviewJpaTest {

  @Autowired AiCodeReviewRepository reviews;

  @Test
  void persistsAndQueriesBySandboxSession() {
    long sid = System.nanoTime();
    AiCodeReview r = new AiCodeReview();
    r.setSandboxSessionId(sid);
    r.setUserId(42L);
    r.setStatus("PENDING");
    AiCodeReview saved = reviews.save(r);

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getStrengths()).isEqualTo("[]"); // JSONB 기본값
    assertThat(reviews.existsBySandboxSessionId(sid)).isTrue();
    assertThat(reviews.findBySandboxSessionId(sid)).get()
        .extracting(AiCodeReview::getUserId).isEqualTo(42L);

    reviews.delete(saved);
  }
}
