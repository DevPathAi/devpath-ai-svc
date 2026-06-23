package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ReviewServiceTest {

  @Autowired ReviewService service;
  @Autowired AiCodeReviewRepository reviews;
  @MockitoBean SandboxClient sandboxClient;

  @Test
  void createsDoneReviewFromCompletedSession() {
    long sid = System.nanoTime();
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 42L, "PYTHON", 3L, "print(1)", "1\n", "", 0, "COMPLETED"));

    service.reviewRun(sid, 42L, 3L);

    AiCodeReview r = reviews.findBySandboxSessionId(sid).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("DONE");
    assertThat(r.getProvider()).isEqualTo("MOCK");
    assertThat(r.getConfidence()).isGreaterThanOrEqualTo(70);
    reviews.delete(r);
  }

  @Test
  void duplicateEventIsIdempotent() {
    long sid = System.nanoTime();
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 7L, "PYTHON", null, "x", "", "", 0, "COMPLETED"));

    service.reviewRun(sid, 7L, null);
    service.reviewRun(sid, 7L, null); // 재전달

    assertThat(reviews.findBySandboxSessionId(sid)).isPresent();
    assertThat(reviews.findAll().stream()
        .filter(x -> x.getSandboxSessionId() == sid).count()).isEqualTo(1L);
    reviews.findBySandboxSessionId(sid).ifPresent(reviews::delete);
  }

  @Test
  void ownershipMismatchFails() {
    long sid = System.nanoTime();
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 999L, "PYTHON", null, "x", "", "", 0, "COMPLETED")); // 세션 user != 이벤트 user

    service.reviewRun(sid, 7L, null);

    AiCodeReview r = reviews.findBySandboxSessionId(sid).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("FAILED");
    assertThat(r.getErrorCode()).isEqualTo("OWNERSHIP_MISMATCH");
    reviews.delete(r);
  }

  @Test
  void sandboxFailureMarksFailed() {
    long sid = System.nanoTime();
    when(sandboxClient.getSession(anyLong()))
        .thenThrow(new SandboxUnavailableException("down", null));

    service.reviewRun(sid, 7L, null);

    AiCodeReview r = reviews.findBySandboxSessionId(sid).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("FAILED");
    assertThat(r.getErrorCode()).isEqualTo("LLM_FAILED");
    reviews.delete(r);
  }
}
