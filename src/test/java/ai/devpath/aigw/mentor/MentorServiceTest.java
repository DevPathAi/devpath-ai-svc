package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MentorServiceTest {

  @Mock MentorContextAssembler contextAssembler;
  @Mock MentorReferenceService referenceService;
  @Mock KnowledgeReferenceService knowledgeService;
  @Mock AiMentorClient mentorClient;
  @Mock MentorPersistenceService persistence;

  private MentorService service() {
    return new MentorService(contextAssembler, referenceService, knowledgeService, mentorClient, persistence,
        JsonMapper.builder().build());
  }

  @Test
  void streamsReferencesThenTokensThenPersistsDone() throws Exception {
    // MentorService가 질문 임베딩을 한 번만 계산해 referenceService.findByEmbedding에 재사용한다
    // (리뷰 Important #2 이후 배선). find(String,String)은 더 이상 호출되지 않으므로 stub 대상을 옮긴다.
    List<Double> embedding = Collections.nCopies(768, 0.1);
    when(contextAssembler.assemble(42L, 7L))
        .thenReturn(new MentorContext("ctx", "{\"track\":\"BACKEND_SPRING\"}", "BACKEND_SPRING"));
    when(referenceService.embedQuestion("비동기란?")).thenReturn(embedding);
    when(referenceService.findByEmbedding(embedding, "BACKEND_SPRING"))
        .thenReturn(List.of(new SimilarContent(1, "a", "t")));
    when(mentorClient.providerName()).thenReturn("MOCK");
    doAnswer(inv -> {
      Consumer<String> sink = inv.getArgument(1);
      sink.accept("비동기는 ");
      sink.accept("Future입니다.");
      return null;
    }).when(mentorClient).stream(any(), any());

    RecordingEmitter emitter = new RecordingEmitter();
    service().streamAnswer(42L, "비동기란?", 7L, emitter);

    assertThat(emitter.events).anyMatch(e -> e.contains("references"));
    assertThat(emitter.events).anyMatch(e -> e.contains("token"));
    verify(persistence).saveDone(eq(42L), eq("비동기란?"), eq(7L),
        eq("비동기는 Future입니다."), anyString(), anyString(), eq("MOCK"));
    assertThat(emitter.completed).isTrue();
  }

  @Test
  void persistsFailedAndEmitsErrorEventThenCompletesOnLlmFailure() throws Exception {
    when(contextAssembler.assemble(42L, null))
        .thenReturn(new MentorContext("ctx", "{}", null));
    // referenceService.embedQuestion/findByEmbedding·knowledgeService.findByEmbedding은 unstubbed 상태로
    // Mockito 기본값(빈 리스트)을 반환한다 — 이 테스트는 LLM 실패 경로만 검증하므로 별도 stub이 불필요하다.
    doAnswer(inv -> { throw new RuntimeException("llm down"); })
        .when(mentorClient).stream(any(), any());

    RecordingEmitter emitter = new RecordingEmitter();
    service().streamAnswer(42L, "q", null, emitter);

    verify(persistence).saveFailed(eq(42L), eq("q"), isNull(), anyString(), anyString());
    assertThat(emitter.events).anyMatch(e -> e.contains("INTERNAL_ERROR"));
    assertThat(emitter.completed).isTrue();
    assertThat(emitter.error).isNull();
  }

  /** SseEmitter 더블: send된 이벤트 문자열과 complete/error를 기록. */
  static final class RecordingEmitter extends SseEmitter {
    final java.util.List<String> events = new java.util.ArrayList<>();
    boolean completed;
    Throwable error;
    @Override public void send(SseEventBuilder builder) {
      builder.build().forEach(d -> events.add(String.valueOf(d.getData())));
    }
    @Override public void complete() { completed = true; }
    @Override public void completeWithError(Throwable ex) { error = ex; }
  }
}
