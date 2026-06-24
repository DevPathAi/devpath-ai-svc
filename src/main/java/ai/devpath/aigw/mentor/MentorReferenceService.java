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
      List<Double> embedding = ollamaClient.embed(List.of(question)).embeddings().get(0);
      return learningClient.searchSimilar(embedding, TOP_K, track);
    } catch (RuntimeException e) {
      return List.of(); // 임베딩/검색 실패 → references 생략, 토큰 스트림은 무관 진행
    }
  }
}
