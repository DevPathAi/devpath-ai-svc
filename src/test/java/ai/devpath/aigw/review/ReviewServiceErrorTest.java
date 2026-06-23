package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** LLM 오류 분류 분기 검증. AiReviewClient를 mock해 Transient/Permanent를 주입. */
@SpringBootTest
@ActiveProfiles("test")
class ReviewServiceErrorTest {

  @Autowired ReviewService service;
  @Autowired AiCodeReviewRepository reviews;
  @MockitoBean SandboxClient sandboxClient;
  @MockitoBean AiReviewClient aiReviewClient;

  @Test
  void transientLlmFailureRethrowsAndKeepsPending() {
    long sid = System.nanoTime();
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 7L, "PYTHON", null, "x", "", "", 0, "COMPLETED"));
    when(aiReviewClient.review(any()))
        .thenThrow(new TransientReviewException("LLM_5XX", "boom", null));

    Assertions.assertThrows(TransientReviewException.class,
        () -> service.reviewRun(sid, 7L, null));

    AiCodeReview r = reviews.findBySandboxSessionId(sid).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("PENDING"); // 재시도 위해 PENDING 유지
    reviews.delete(r);
  }

  @Test
  void permanentLlmFailureMarksParseFailed() {
    long sid = System.nanoTime();
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 7L, "PYTHON", null, "x", "", "", 0, "COMPLETED"));
    when(aiReviewClient.review(any()))
        .thenThrow(new PermanentReviewException("PARSE_FAILED", "bad json", null));

    service.reviewRun(sid, 7L, null);

    AiCodeReview r = reviews.findBySandboxSessionId(sid).orElseThrow();
    assertThat(r.getStatus()).isEqualTo("FAILED");
    assertThat(r.getErrorCode()).isEqualTo("PARSE_FAILED");
    reviews.delete(r);
  }
}
