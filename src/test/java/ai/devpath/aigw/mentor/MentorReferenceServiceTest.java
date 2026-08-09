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

@ExtendWith(MockitoExtension.class)
class MentorReferenceServiceTest {

  @Mock OllamaClient ollamaClient;
  @Mock LearningClient learningClient;

  @Test
  void embedsQuestionAndSearchesSimilar() {
    when(ollamaClient.embed(List.of("비동기란?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));
    when(learningClient.searchSimilar(any(), eq(3), eq("BACKEND_SPRING")))
        .thenReturn(List.of(new SimilarContent(1, "a", "t")));

    var svc = new MentorReferenceService(ollamaClient, learningClient);
    List<SimilarContent> refs = svc.find("비동기란?", "BACKEND_SPRING");

    assertThat(refs).hasSize(1);
    assertThat(refs.get(0).slug()).isEqualTo("a");
  }

  @Test
  void returnsEmptyWhenEmbedFails() {
    when(ollamaClient.embed(any())).thenThrow(new RuntimeException("ollama down"));

    var svc = new MentorReferenceService(ollamaClient, learningClient);
    assertThat(svc.find("q", null)).isEmpty();
  }

  @Test
  void findByEmbeddingSkipsReEmbeddingAndDelegatesDirectly() {
    var embedding = Collections.nCopies(768, 0.1);
    when(learningClient.searchSimilar(embedding, 3, "BACKEND_SPRING"))
        .thenReturn(List.of(new SimilarContent(1, "a", "t")));

    var svc = new MentorReferenceService(ollamaClient, learningClient);
    List<SimilarContent> refs = svc.findByEmbedding(embedding, "BACKEND_SPRING");

    assertThat(refs).hasSize(1);
    assertThat(refs.get(0).slug()).isEqualTo("a");
  }

  @Test
  void embedQuestionReturnsRawEmbeddingForReuseByCaller() {
    when(ollamaClient.embed(List.of("비동기란?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));

    var svc = new MentorReferenceService(ollamaClient, learningClient);
    List<Double> embedding = svc.embedQuestion("비동기란?");

    assertThat(embedding).hasSize(768);
  }

  @Test
  void findByEmbeddingReturnsEmptyListWhenClientThrowsUnexpectedRuntimeException() {
    // RestClientException은 LearningClient.searchSimilar 내부 catch에서 이미 빈 리스트로 걸러지므로
    // 여기선 그 catch를 통과하는 종류(RestClientException이 아닌 RuntimeException)를 던져야
    // findByEmbedding 자체의 방어(재리뷰 Minor 조치)가 실제로 동작하는지 검증할 수 있다.
    var embedding = Collections.nCopies(768, 0.1);
    when(learningClient.searchSimilar(embedding, 3, "BACKEND_SPRING"))
        .thenThrow(new IllegalStateException("역직렬화 버그"));

    var svc = new MentorReferenceService(ollamaClient, learningClient);
    List<SimilarContent> refs = svc.findByEmbedding(embedding, "BACKEND_SPRING");

    assertThat(refs).isEmpty();
  }
}
