package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class MentorServiceKnowledgeInjectionTest {

  /** 전달받은 MentorInput을 붙잡아 두는 가짜 클라이언트. */
  private static final class CapturingClient implements AiMentorClient {
    final AtomicReference<MentorInput> captured = new AtomicReference<>();

    @Override
    public void stream(MentorInput input, Consumer<String> tokenSink) {
      captured.set(input);
      tokenSink.accept("답변");
    }

    @Override
    public String providerName() {
      return "CAPTURING";
    }
  }

  @Test
  void putsKnowledgeChunksIntoTheMentorInput() {
    var contextAssembler = mock(MentorContextAssembler.class);
    when(contextAssembler.assemble(anyLong(), any()))
        .thenReturn(new MentorContext("맥락", "{}", "BACKEND_SPRING"));

    // MentorService가 질문 임베딩을 한 번만 계산해 findByEmbedding에 재사용한다(리뷰 Important #2 이후
    // 배선). find(String,String)/find(String)은 더 이상 호출되지 않으므로 stub 대상을 findByEmbedding으로 옮긴다.
    var referenceService = mock(MentorReferenceService.class);
    when(referenceService.findByEmbedding(any(), any())).thenReturn(List.of());

    var knowledgeService = mock(KnowledgeReferenceService.class);
    var chunk = new KnowledgeChunk("AWS/a.md", "AWS 개념", "AWS", "근거 본문", 0.1);
    when(knowledgeService.findByEmbedding(any())).thenReturn(List.of(chunk));

    var client = new CapturingClient();
    var persistence = mock(MentorPersistenceService.class);
    var service = new MentorService(contextAssembler, referenceService, knowledgeService,
        client, persistence, JsonMapper.builder().build());

    service.streamAnswer(1L, "Pod Identity가 뭔가요?", null, new SseEmitter());

    assertThat(client.captured.get()).isNotNull();
    assertThat(client.captured.get().referenceDocs()).containsExactly(chunk);
  }

  @Test
  void knowledgeFailureDoesNotStopTheAnswer() {
    var contextAssembler = mock(MentorContextAssembler.class);
    when(contextAssembler.assemble(anyLong(), any()))
        .thenReturn(new MentorContext("맥락", "{}", null));

    var referenceService = mock(MentorReferenceService.class);
    when(referenceService.findByEmbedding(any(), any())).thenReturn(List.of());

    var knowledgeService = mock(KnowledgeReferenceService.class);
    when(knowledgeService.findByEmbedding(any())).thenReturn(List.of());   // 검색 실패 → 빈 리스트

    var client = new CapturingClient();
    var service = new MentorService(contextAssembler, referenceService, knowledgeService,
        client, mock(MentorPersistenceService.class), JsonMapper.builder().build());

    service.streamAnswer(1L, "질문", null, new SseEmitter());

    assertThat(client.captured.get().referenceDocs()).isEmpty();
  }
}
