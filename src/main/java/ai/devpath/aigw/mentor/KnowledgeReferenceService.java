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
      return knowledgeClient.searchSimilar(embedding, TOP_K);
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
