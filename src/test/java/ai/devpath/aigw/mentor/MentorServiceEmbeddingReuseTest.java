package ai.devpath.aigw.mentor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.ollama.OllamaClient;
import ai.devpath.aigw.ollama.dto.EmbedResponse;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 질문 임베딩이 멘토 요청 1건당 정확히 1회만 계산되는지 회귀 검증(리뷰 Important #2).
 * MentorReferenceService·KnowledgeReferenceService가 각자 독립적으로
 * {@code ollamaClient.embed(...)}를 호출하도록 되돌아가면 이 테스트가 실패한다. 실제 협력 객체
 * (MentorReferenceService·KnowledgeReferenceService)는 mock하지 않고 그대로 사용해, MentorService가
 * embedQuestion을 한 번만 호출해 두 서비스의 findByEmbedding에 재사용하는 배선 자체를 검증한다.
 */
class MentorServiceEmbeddingReuseTest {

  @Test
  void embedsQuestionExactlyOncePerRequest() {
    var ollamaClient = mock(OllamaClient.class);
    when(ollamaClient.embed(any()))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));

    var referenceService = new MentorReferenceService(ollamaClient, mock(LearningClient.class));
    var knowledgeService = new KnowledgeReferenceService(ollamaClient, mock(KnowledgeClient.class));

    var contextAssembler = new MentorContextAssembler();

    var service = new MentorService(contextAssembler, referenceService, knowledgeService,
        mock(AiMentorClient.class), mock(MentorPersistenceService.class), JsonMapper.builder().build());

    service.streamAnswer(1L, "질문", null, null, mock(SseEmitter.class));

    verify(ollamaClient, times(1)).embed(any());
  }
}
