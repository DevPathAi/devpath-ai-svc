package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class FallbackMentorClientTest {

  /** 지정 토큰을 방출하거나, 지정 개수만큼 방출 후 예외를 던지는 가짜 delegate. */
  static final class FakeClient implements AiMentorClient {
    final String name;
    final List<String> tokens;
    final int emitThenFail; // -1이면 실패 없음, N이면 N개 방출 후 예외
    FakeClient(String name, List<String> tokens, int emitThenFail) {
      this.name = name; this.tokens = tokens; this.emitThenFail = emitThenFail;
    }
    @Override public void stream(MentorInput input, Consumer<String> sink) {
      int i = 0;
      for (String t : tokens) {
        if (emitThenFail >= 0 && i >= emitThenFail) throw new RuntimeException(name + " failed");
        sink.accept(t); i++;
      }
      if (emitThenFail >= 0 && tokens.size() <= emitThenFail) return;
    }
    @Override public String providerName() { return name; }
  }

  private final MentorInput input = new MentorInput("q", "ctx");

  @Test
  void primarySucceeds_noFallback_reportsPrimary() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("a", "b"), -1);
    FakeClient secondary = new FakeClient("MOCK", List.of("x"), -1);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));
    List<String> out = new ArrayList<>();

    client.stream(input, out::add);

    assertThat(out).containsExactly("a", "b");
    assertThat(client.providerName()).isEqualTo("OLLAMA");
  }

  @Test
  void primaryFailsBeforeEmit_fallsBackToSecondary() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("nope"), 0); // 방출 전 실패
    FakeClient secondary = new FakeClient("MOCK", List.of("x", "y"), -1);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));
    List<String> out = new ArrayList<>();

    client.stream(input, out::add);

    assertThat(out).containsExactly("x", "y");
    assertThat(client.providerName()).isEqualTo("MOCK");
  }

  @Test
  void primaryFailsAfterEmit_rethrows_noFallback() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("a", "b", "c"), 1); // 1개 방출 후 실패
    FakeClient secondary = new FakeClient("MOCK", List.of("x"), -1);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));
    List<String> out = new ArrayList<>();

    assertThatThrownBy(() -> client.stream(input, out::add))
        .isInstanceOf(RuntimeException.class);
    assertThat(out).containsExactly("a"); // primary가 방출한 토큰만, 폴백 안 함
  }

  @Test
  void allFailBeforeEmit_throws() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("n"), 0);
    FakeClient secondary = new FakeClient("CLAUDE", List.of("n"), 0);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));

    assertThatThrownBy(() -> client.stream(input, t -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void emptyDelegates_rejected() {
    assertThatThrownBy(() -> new FallbackMentorClient(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
