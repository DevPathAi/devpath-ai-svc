package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MentorTimeoutPolicyTest {

  @Test
  void providerDeadlineIsStrictlyInsideRequestAndSseDeadlines() {
    MentorTimeoutPolicy policy = new MentorTimeoutPolicy(
        Duration.ofSeconds(50), Duration.ofSeconds(55), Duration.ofSeconds(60));

    assertThat(policy.providerTimeout()).isLessThan(policy.requestTimeout());
    assertThat(policy.requestTimeout()).isLessThan(policy.sseTimeout());
  }

  @Test
  void rejectsEqualReversedOrNonPositiveDeadlines() {
    assertThatThrownBy(() -> new MentorTimeoutPolicy(
        Duration.ofSeconds(55), Duration.ofSeconds(55), Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MentorTimeoutPolicy(
        Duration.ofSeconds(56), Duration.ofSeconds(55), Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MentorTimeoutPolicy(
        Duration.ZERO, Duration.ofSeconds(55), Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MentorTimeoutPolicy(
        Duration.ofSeconds(50), Duration.ofSeconds(61), Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
