package ai.devpath.aigw.mentor;

import java.util.List;

/**
 * 멘토 입력.
 *
 * <p>referenceDocs는 지식베이스에서 검색된 문서 청크다. 이 본문이 프롬프트에 근거로 주입된다.
 * 2-인자 생성자는 기존 호출부를 보존하기 위해 남긴다 — record는 필드를 더하면 접근자는
 * 호환되지만 생성자는 호환되지 않는다.
 */
public record MentorInput(String question, String contextText, List<KnowledgeChunk> referenceDocs) {

  public MentorInput {
    referenceDocs = referenceDocs == null ? List.of() : List.copyOf(referenceDocs);
  }

  public MentorInput(String question, String contextText) {
    this(question, contextText, List.of());
  }
}
