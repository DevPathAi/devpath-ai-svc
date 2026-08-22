package ai.devpath.aigw.mentor;

import java.util.function.Consumer;

/** 멘토 LLM 스트리밍 추상화. 토큰을 tokenSink로 push, 완료 시 반환, 실패 시 RuntimeException. */
public interface AiMentorClient {
  void stream(MentorInput input, Consumer<String> tokenSink);

  /** Reports the selected provider before that provider can emit its first token. */
  default void stream(MentorInput input, Consumer<String> tokenSink,
      Consumer<String> providerSelected) {
    providerSelected.accept(providerName());
    stream(input, tokenSink);
  }

  String providerName();
}
