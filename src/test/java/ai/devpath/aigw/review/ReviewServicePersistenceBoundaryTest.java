package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewServicePersistenceBoundaryTest {

  @Test
  void terminalWriteFailureIsNotMisclassifiedAsProviderFailure() {
    ReviewPersistenceService persistence = mock(ReviewPersistenceService.class);
    SandboxClient sandboxClient = mock(SandboxClient.class);
    AiReviewClient aiReviewClient = mock(AiReviewClient.class);
    ReviewService service = new ReviewService(
        persistence, sandboxClient, aiReviewClient, Duration.ofMinutes(5));
    UUID eventId = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    ReviewClaim claim = new ReviewClaim(1L, 11L, eventId, token);
    ReviewResult result = new ReviewResult(90, List.of("clear"), List.of(), List.of());

    when(persistence.claim(eventId, 11L, 42L, 3L, Duration.ofMinutes(5)))
        .thenReturn(Optional.of(claim));
    when(sandboxClient.getSession(11L)).thenReturn(new SandboxSessionView(
        11L, 42L, "PYTHON", 3L, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(aiReviewClient.review(any())).thenReturn(result);
    when(aiReviewClient.providerName()).thenReturn("MOCK");
    when(persistence.finishDone(claim, result, "MOCK"))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> service.reviewRun(eventId, 11L, 42L, 3L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database unavailable");

    verify(persistence, never()).finishFailed(eq(claim), any());
  }
}
