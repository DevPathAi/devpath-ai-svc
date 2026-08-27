package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.release.ReleaseJourneyRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewServiceReleaseFaultTest {
  @Test
  void injectedFailureIsVisibleButKeepsTheKafkaDeliveryHeldForExactReplay() {
    String candidate = "a".repeat(64);
    String runKey = "R".repeat(43);
    UUID eventId = UUID.randomUUID();
    ReviewClaim claim = new ReviewClaim(1L, 81L, eventId, UUID.randomUUID());
    ReviewPersistenceService persistence = mock(ReviewPersistenceService.class);
    SandboxClient sandbox = mock(SandboxClient.class);
    AiReviewClient provider = mock(AiReviewClient.class);
    ReleaseJourneyRegistry release = new ReleaseJourneyRegistry(
        true, JsonMapper.builder().build());
    release.arm(candidate, runKey, 42L, "fail-next-review", 80L);
    ReviewService service = new ReviewService(
        persistence, sandbox, provider, Duration.ofMinutes(5), release);

    when(persistence.claim(eventId, 81L, 42L, 7L, Duration.ofMinutes(5)))
        .thenReturn(Optional.of(claim));
    when(sandbox.getSession(81L)).thenReturn(new SandboxSessionView(
        81L, 42L, "PYTHON", 7L, "print(1)", "1\n", "", 0, "COMPLETED"));
    when(persistence.finishReleaseFailed(claim)).thenReturn(true);

    assertThat(service.reviewRun(eventId, 81L, 42L, 7L))
        .isEqualTo(ReviewDisposition.IN_PROGRESS);
    assertThat(release.holdReview(eventId, 81L, 42L)).isTrue();
    verify(provider, never()).review(any());
  }
}
