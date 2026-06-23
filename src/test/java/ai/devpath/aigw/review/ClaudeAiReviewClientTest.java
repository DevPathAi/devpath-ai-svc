package ai.devpath.aigw.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClaudeAiReviewClientTest {

  @Test
  void providerNameIsClaude() {
    var client = new ClaudeAiReviewClient(null, "claude-sonnet-4-6", new ReviewPromptBuilder());
    assertThat(client.providerName()).isEqualTo("CLAUDE");
  }
}
