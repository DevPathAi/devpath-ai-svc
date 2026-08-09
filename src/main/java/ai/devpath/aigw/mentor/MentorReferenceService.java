package ai.devpath.aigw.mentor;

import ai.devpath.aigw.ollama.OllamaClient;
import java.util.List;
import org.springframework.stereotype.Service;

/** 참고자료(M-4): 질문 임베딩(ai-svc 자체 Ollama embed) → learning 유사검색. 실패는 빈 리스트(M-2 독립). */
@Service
public class MentorReferenceService {

  private static final int TOP_K = 3;

  private final OllamaClient ollamaClient;
  private final LearningClient learningClient;

  public MentorReferenceService(OllamaClient ollamaClient, LearningClient learningClient) {
    this.ollamaClient = ollamaClient;
    this.learningClient = learningClient;
  }

  public List<SimilarContent> find(String question, String track) {
    try {
      return findByEmbedding(embedQuestion(question), track);
    } catch (RuntimeException e) {
      return List.of(); // 임베딩/검색 실패 → references 생략, 토큰 스트림은 무관 진행
    }
  }

  /**
   * 미리 계산된 임베딩으로 검색한다(질문 임베딩 중복 계산 방지, 리뷰 Important #2).
   * {@code searchSimilar}가 {@code RestClientException}은 내부에서 잡아 빈 리스트로 폴백하지만, 그 외
   * 예기치 않은 RuntimeException(예: 역직렬화 버그성 예외)까지 여기서 잡아야 "검색 실패는 빈 리스트,
   * 답변은 계속" 불변식이 이 진입점(리뷰 재리뷰 Minor)에서도 지켜진다.
   */
  public List<SimilarContent> findByEmbedding(List<Double> embedding, String track) {
    try {
      return learningClient.searchSimilar(embedding, TOP_K, track);
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /**
   * 질문 임베딩만 계산해 호출자(MentorService)가 지식베이스 검색({@link KnowledgeReferenceService})과
   * 공유할 수 있게 한다. 실패 시 예외를 그대로 전파 — 호출자가 잡아 빈 리스트로 폴백한다.
   */
  public List<Double> embedQuestion(String question) {
    return ollamaClient.embed(List.of(question)).embeddings().get(0);
  }
}
