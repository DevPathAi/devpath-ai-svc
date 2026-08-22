package ai.devpath.aigw.mentor;

import java.util.List;
import java.util.function.Consumer;

/**
 * 순서형 폴백 멘토 클라이언트. delegate를 순서대로 시도하되, 어떤 delegate가 토큰을 하나라도
 * 방출한 뒤 실패하면 폴백하지 않고 예외를 전파한다(스트리밍 중간 전환 불가). 실제 응답한 provider는
 * legacy 2-argument stream() 경로에서만 ThreadLocal에 기록한다. callback-aware 경로는 선택을 즉시
 * 보고하고 pooled worker thread에 상태를 남기지 않는다.
 */
public class FallbackMentorClient implements AiMentorClient {

  private static final ThreadLocal<String> SERVED = new ThreadLocal<>();

  private final List<AiMentorClient> delegates;

  public FallbackMentorClient(List<AiMentorClient> delegates) {
    if (delegates == null || delegates.isEmpty()) {
      throw new IllegalArgumentException("delegates must not be empty");
    }
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public void stream(MentorInput input, Consumer<String> tokenSink) {
    SERVED.remove();
    streamDelegates(input, tokenSink, SERVED::set);
  }

  @Override
  public void stream(MentorInput input, Consumer<String> tokenSink,
      Consumer<String> providerSelected) {
    SERVED.remove();
    try {
      streamDelegates(input, tokenSink, providerSelected);
    } finally {
      SERVED.remove();
    }
  }

  private void streamDelegates(MentorInput input, Consumer<String> tokenSink,
      Consumer<String> providerSelected) {
    boolean[] emitted = {false};
    Consumer<String> guarded = t -> { emitted[0] = true; tokenSink.accept(t); };
    RuntimeException last = null;
    for (AiMentorClient d : delegates) {
      String selected = d.providerName();
      providerSelected.accept(selected);
      try {
        d.stream(input, guarded);
        return;
      } catch (RuntimeException e) {
        if (emitted[0]) throw e; // 이미 스트리밍 시작 → 폴백 불가
        last = e;                // 방출 전 실패 → 다음 delegate
      }
    }
    throw (last != null ? last : new IllegalStateException("no mentor delegate available"));
  }

  @Override
  public String providerName() {
    String s = SERVED.get();
    SERVED.remove();
    return s != null ? s : delegates.get(0).providerName();
  }
}
