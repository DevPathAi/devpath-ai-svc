package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  @Mock AiMentorClient mentorClient;
  @Mock MentorPersistenceService persistence;

  private MentorService service() {
    return new MentorService(contextAssembler, referenceService, mentorClient, persistence,
        JsonMapper.builder().build());
  }

  @Test
  void streamsReferencesThenTokensThenPersistsDone() throws Exception {
    when(contextAssembler.assemble(42L, 7L))
        .thenReturn(new MentorContext("ctx", "{\"track\":\"BACKEND_SPRING\"}", "BACKEND_SPRING"));
    when(referenceService.find("비동기란?", "BACKEND_SPRING"))
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
  void persistsFailedAndCompletesWithErrorOnLlmFailure() throws Exception {
    when(contextAssembler.assemble(42L, null))
        .thenReturn(new MentorContext("ctx", "{}", null));
    when(referenceService.find(anyString(), isNull())).thenReturn(List.of());
    doAnswer(inv -> { throw new RuntimeException("llm down"); })
        .when(mentorClient).stream(any(), any());

    RecordingEmitter emitter = new RecordingEmitter();
    service().streamAnswer(42L, "q", null, emitter);

    verify(persistence).saveFailed(eq(42L), eq("q"), isNull(), anyString(), anyString());
    assertThat(emitter.error).isNotNull();
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
