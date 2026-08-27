package ai.devpath.aigw.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.review.ReviewPersistenceService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiReleaseControllerTest {
  @Test
  void armsOwnerBoundFaultAndReopensOnlyTheRecordedReviewOnClear() {
    String candidate = "a".repeat(64);
    String runKey = "R".repeat(43);
    ReleaseJourneyRegistry registry = mock(ReleaseJourneyRegistry.class);
    ReviewPersistenceService persistence = mock(ReviewPersistenceService.class);
    AiReleaseController controller = new AiReleaseController(registry, persistence);
    var replay = new ReleaseJourneyRegistry.ReviewReplay(
        UUID.randomUUID(), 81L, 42L, 7L);

    assertThat(controller.command(
        candidate, runKey, "fail-next-review", Map.of(
            "user_id", 42L,
            "prior_sandbox_session_id", 80L)))
        .containsEntry("accepted", true);
    verify(registry).arm(candidate, runKey, 42L, "fail-next-review", 80L);

    when(registry.clear(candidate, runKey, 42L)).thenReturn(Optional.of(replay));
    when(persistence.reopenReleaseFailure(replay)).thenReturn(true);
    assertThat(controller.command(
        candidate, runKey, "clear-faults", Map.of("user_id", 42L)))
        .containsEntry("accepted", true);
    verify(persistence).reopenReleaseFailure(replay);
    verify(registry).recordReviewReleased(replay);
  }

  @Test
  void rejectsReviewFaultWithoutAPositivePriorSession() {
    ReleaseJourneyRegistry registry = mock(ReleaseJourneyRegistry.class);
    AiReleaseController controller = new AiReleaseController(
        registry, mock(ReviewPersistenceService.class));

    assertThatThrownBy(() -> controller.command(
        "a".repeat(64), "R".repeat(43), "fail-next-review", Map.of("user_id", 42L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prior sandbox session");
    assertThatThrownBy(() -> controller.command(
        "a".repeat(64), "R".repeat(43), "fail-next-review", Map.of(
            "user_id", 42L,
            "prior_sandbox_session_id", 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prior sandbox session");
  }
}
