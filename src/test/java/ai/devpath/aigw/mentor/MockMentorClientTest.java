package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockMentorClientTest {

  @Test
  void streamsTokensAndReportsProvider() {
    MockMentorClient client = new MockMentorClient();
    List<String> tokens = new ArrayList<>();

    client.stream(new MentorInput("비동기란?", "ctx"), tokens::add);

    assertThat(client.providerName()).isEqualTo("MOCK");
    assertThat(tokens).isNotEmpty();
    assertThat(String.join("", tokens)).isNotBlank();
  }
}
