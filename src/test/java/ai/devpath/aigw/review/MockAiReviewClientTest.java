package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MockAiReviewClientTest {

  private final MockAiReviewClient client = new MockAiReviewClient();

  @Test
  void successfulRunGetsHighConfidenceNoIssues() {
    ReviewResult r = client.review(new ReviewInput("PYTHON", "print(1)", "1\n", "", 0));
    assertThat(client.providerName()).isEqualTo("MOCK");
    assertThat(r.confidence()).isGreaterThanOrEqualTo(70);
    assertThat(r.improvements()).isEmpty();
  }

  @Test
  void failedRunIsGroundedInExitCode() {
    ReviewResult r = client.review(new ReviewInput("PYTHON", "boom", "", "Traceback\n", 1));
    assertThat(r.confidence()).isLessThan(70);
    assertThat(r.improvements()).isNotEmpty();
  }
}
