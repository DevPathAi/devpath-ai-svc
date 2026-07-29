package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MentorClientConfigTest {

  static final class Stub implements AiMentorClient {
    final String name;
    Stub(String name) { this.name = name; }
    @Override public void stream(MentorInput in, Consumer<String> sink) {}
    @Override public String providerName() { return name; }
  }

  private Map<String, AiMentorClient> available(String... names) {
    Map<String, AiMentorClient> m = new LinkedHashMap<>();
    for (String n : names) m.put(n, new Stub(n));
    return m;
  }

  private List<String> names(List<AiMentorClient> chain) {
    return chain.stream().map(AiMentorClient::providerName).toList();
  }

  @Test
  void fullChain_ollamaClaudeMock() {
    var chain = MentorClientConfig.orderedChain(
        "ollama", "claude,mock", available("ollama", "claude", "mock"));
    assertThat(names(chain)).containsExactly("ollama", "claude", "mock");
  }

  @Test
  void claudeUnavailable_isFilteredOut() {
    var chain = MentorClientConfig.orderedChain(
        "ollama", "claude,mock", available("ollama", "mock")); // claude 없음(무키)
    assertThat(names(chain)).containsExactly("ollama", "mock");
  }

  @Test
  void singleProvider_emptyFallback() {
    var chain = MentorClientConfig.orderedChain(
        "mock", "", available("ollama", "mock"));
    assertThat(names(chain)).containsExactly("mock");
  }

  @Test
  void deduplicatesPreservingOrder() {
    var chain = MentorClientConfig.orderedChain(
        "ollama", "ollama, mock", available("ollama", "mock"));
    assertThat(names(chain)).containsExactly("ollama", "mock");
  }

  @Test
  void unknownPrimary_fallsToAvailableFallback() {
    var chain = MentorClientConfig.orderedChain(
        "claude", "mock", available("ollama", "mock")); // claude 없음
    assertThat(names(chain)).containsExactly("mock");
  }
}
