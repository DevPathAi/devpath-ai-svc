package ai.devpath.aigw.mentor;

import ai.devpath.aigw.ollama.OllamaClient;
import java.util.List;
import org.springframework.stereotype.Service;

/** 지식베이스 근거 조회: 질문 임베딩 → learning 지식 검색. 실패는 빈 리스트(답변은 계속). */
@Service
public class KnowledgeReferenceService {

  private static final int TOP_K = 3;

  private final OllamaClient ollamaClient;
  private final KnowledgeClient knowledgeClient;

  public KnowledgeReferenceService(OllamaClient ollamaClient, KnowledgeClient knowledgeClient) {
    this.ollamaClient = ollamaClient;
    this.knowledgeClient = knowledgeClient;
  }

  public List<KnowledgeChunk> find(String question) {
    try {
      List<Double> embedding = ollamaClient.embed(List.of(question)).embeddings().get(0);
      return findByEmbedding(embedding);
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /**
   * 미리 계산된 임베딩으로 검색한다(질문 임베딩 중복 계산 방지, 리뷰 Important #2). MentorService가
   * {@link MentorReferenceService#embedQuestion(String)}으로 한 번만 계산한 임베딩을 여기 재사용한다.
   * {@code searchSimilar}가 {@code RestClientException}은 내부에서 잡아 빈 리스트로 폴백하지만, 그 외
   * 예기치 않은 RuntimeException(예: 역직렬화 버그성 예외)까지 여기서 잡아야 "검색 실패는 빈 리스트,
   * 답변은 계속" 불변식이 이 진입점(리뷰 재리뷰 Minor)에서도 지켜진다.
   */
  public List<KnowledgeChunk> findByEmbedding(List<Double> embedding) {
    try {
      return knowledgeClient.searchSimilar(embedding, TOP_K);
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
