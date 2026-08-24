package ai.devpath.aigw.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.aigw.mentor.KnowledgeChunk;
import ai.devpath.aigw.mentor.MentorInput;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReleaseJourneyRegistryTest {
  private static final String CANDIDATE = "a".repeat(64);
  private static final String RUN = "R".repeat(43);

  @Test
  void reviewFaultIsBoundToOneUserAndHeldUntilExactClear() {
    ReleaseJourneyRegistry registry = new ReleaseJourneyRegistry(true,
        JsonMapper.builder().build());
    UUID eventId = UUID.randomUUID();

    registry.arm(CANDIDATE, RUN, 42L, "fail-next-review");
    var fault = registry.consumeReview(42L, eventId, 81L, 7L).orElseThrow();
    registry.recordReviewFailure(fault);

    assertThat(registry.holdReview(eventId, 81L, 42L)).isTrue();
    assertThat(registry.checkpoint(
        CANDIDATE, RUN, "partial-review-retains-run-and-review")).isTrue();
    assertThat(registry.clear(CANDIDATE, RUN, 42L)).contains(fault.replay());
    assertThat(registry.holdReview(eventId, 81L, 42L)).isTrue();
    registry.recordReviewReleased(fault.replay());
    assertThat(registry.holdReview(eventId, 81L, 42L)).isFalse();

    registry.recordReviewCompleted(eventId, 81L, 42L);
    assertThat(registry.checkpoint(
        CANDIDATE, RUN, "kafka-outbox-review-correlated")).isTrue();
  }

  @Test
  void mentorFailureKeepsPartialAndComparesOnlyHashedProviderPayload() {
    ReleaseJourneyRegistry registry = new ReleaseJourneyRegistry(true,
        JsonMapper.builder().build());
    MentorInput input = new MentorInput(
        "private question",
        "{\"fieldsIncluded\":[\"current_code\"],\"content\":{\"current_code\":\"secret\"}}",
        List.of(new KnowledgeChunk("source", "title", "category", "text", 0.8)));
    List<String> delivered = new ArrayList<>();

    registry.arm(CANDIDATE, RUN, 42L, "fail-next-mentor");
    var first = registry.mentorAttempt(CANDIDATE, RUN, 42L, true);
    registry.recordMentorPayload(first, input);
    assertThatThrownBy(() -> registry.deliverMentorToken(first, delivered::add, "partial"))
        .isInstanceOf(ReleaseInjectedMentorException.class)
        .hasMessage("release mentor fault");
    registry.recordMentorFailed(first, true);

    assertThat(registry.checkpoint(
        CANDIDATE, RUN, "private-mentor-prompt-committed")).isTrue();
    assertThat(registry.checkpoint(CANDIDATE, RUN, "mentor-partial-retained")).isTrue();
    assertThat(delivered).containsExactly("partial");
    registry.clear(CANDIDATE, RUN, 42L);

    var retry = registry.mentorAttempt(CANDIDATE, RUN, 42L, true);
    registry.recordMentorPayload(retry, input);
    registry.deliverMentorToken(retry, delivered::add, "complete");
    registry.recordMentorDone(retry, true);

    assertThat(registry.checkpoint(
        CANDIDATE, RUN, "mentor-provider-payload-exact")).isTrue();
    assertThat(registry.checkpoint(CANDIDATE, RUN, "mentor-terminal-complete")).isTrue();
    assertThat(registry.snapshot(CANDIDATE, RUN).toString())
        .doesNotContain("private question", "current_code", "secret", "partial", "complete");
  }

  @Test
  void disabledOrUnboundTrafficCannotConsumeFaults() {
    ReleaseJourneyRegistry disabled = new ReleaseJourneyRegistry(false,
        JsonMapper.builder().build());
    assertThatThrownBy(() -> disabled.arm(CANDIDATE, RUN, 42L, "fail-next-review"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(disabled.consumeReview(42L, UUID.randomUUID(), 1L, null)).isEmpty();
    assertThat(disabled.mentorAttempt(CANDIDATE, RUN, 42L, true).tracked()).isFalse();
  }
}
