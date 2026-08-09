package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.ollama.OllamaClient;
import ai.devpath.aigw.ollama.dto.EmbedResponse;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** KnowledgeReferenceService 직접 테스트(리뷰 Important #1). MentorReferenceServiceTest와 동일한 패턴. */
@ExtendWith(MockitoExtension.class)
class KnowledgeReferenceServiceTest {

  @Mock OllamaClient ollamaClient;
  @Mock KnowledgeClient knowledgeClient;

  @Test
  void embedsQuestionAndSearchesKnowledgeBase() {
    when(ollamaClient.embed(List.of("Pod Identity가 뭔가요?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));
    var chunk = new KnowledgeChunk("AWS/a.md", "AWS 개념", "AWS", "근거 본문", 0.1);
    when(knowledgeClient.searchSimilar(any(), eq(3))).thenReturn(List.of(chunk));

    var svc = new KnowledgeReferenceService(ollamaClient, knowledgeClient);
    List<KnowledgeChunk> result = svc.find("Pod Identity가 뭔가요?");

    assertThat(result).containsExactly(chunk);
  }

  @Test
  void returnsEmptyListWhenEmbeddingFails() {
    when(ollamaClient.embed(any())).thenThrow(new RuntimeException("ollama down"));

    var svc = new KnowledgeReferenceService(ollamaClient, knowledgeClient);
    assertThat(svc.find("질문")).isEmpty();
  }

  @Test
  void findByEmbeddingSkipsReEmbeddingAndDelegatesDirectly() {
    var embedding = Collections.nCopies(768, 0.1);
    var chunk = new KnowledgeChunk("AWS/a.md", "AWS 개념", "AWS", "근거 본문", 0.1);
    when(knowledgeClient.searchSimilar(embedding, 3)).thenReturn(List.of(chunk));

    var svc = new KnowledgeReferenceService(ollamaClient, knowledgeClient);
    List<KnowledgeChunk> result = svc.findByEmbedding(embedding);

    assertThat(result).containsExactly(chunk);
  }

  @Test
  void findByEmbeddingReturnsEmptyListWhenClientThrowsUnexpectedRuntimeException() {
    // RestClientException은 KnowledgeClient.searchSimilar 내부 catch에서 이미 빈 리스트로 걸러지므로
    // 여기선 그 catch를 통과하는 종류(RestClientException이 아닌 RuntimeException)를 던져야
    // findByEmbedding 자체의 방어(재리뷰 Minor 조치)가 실제로 동작하는지 검증할 수 있다.
    var embedding = Collections.nCopies(768, 0.1);
    when(knowledgeClient.searchSimilar(embedding, 3)).thenThrow(new IllegalStateException("역직렬화 버그"));

    var svc = new KnowledgeReferenceService(ollamaClient, knowledgeClient);
    List<KnowledgeChunk> result = svc.findByEmbedding(embedding);

    assertThat(result).isEmpty();
  }
}
