package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClaudeMentorClientTest {

  @Test
  void providerNameIsClaude() {
    var client = new ClaudeMentorClient(null, "claude-sonnet-4-6", new MentorPromptBuilder());
    assertThat(client.providerName()).isEqualTo("CLAUDE");
  }
}
