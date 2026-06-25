package ai.devpath.aigw.mentor;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** CI/dev 기본 멘토(외부 LLM 없음). 고정 답변을 토큰 분할 방출. */
@Component
@ConditionalOnProperty(name = "devpath.mentor.provider", havingValue = "mock", matchIfMissing = true)
public class MockMentorClient implements AiMentorClient {

  private static final List<String> TOKENS = List.of(
      "그 질문", "에 답하면, ", "비동기는 ", "Future/async/await", "로 다룹니다.");

  @Override
  public void stream(MentorInput input, Consumer<String> tokenSink) {
    for (String t : TOKENS) {
      tokenSink.accept(t);
    }
  }

  @Override
  public String providerName() { return "MOCK"; }
}
