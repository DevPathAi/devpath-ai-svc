package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    MentorSnapshotContext approved = new MentorSnapshotContext(23L,
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\","
            + "\"fieldsIncluded\":[\"current_code\"],\"content\":{\"current_code\":\"code\"}}",
        "{\"fieldsIncluded\":[\"current_code\"],\"content\":{\"current_code\":\"code\"}}");
    when(contextAssembler.assemble(approved))
        .thenReturn(new MentorContext(
            approved.providerContextJson(), approved.envelopeJson(), null));
    when(referenceService.embedQuestion("비동기란?")).thenReturn(embedding);
    when(referenceService.findByEmbedding(embedding, null))
        .thenReturn(List.of(new SimilarContent(1, "a", "t")));
    when(mentorClient.providerName()).thenReturn("MOCK");
    doAnswer(inv -> {
      Consumer<String> sink = inv.getArgument(1);
      sink.accept("비동기는 ");
      sink.accept("Future입니다.");
      return null;
    }).when(mentorClient).stream(any(), any());

    RecordingEmitter emitter = new RecordingEmitter();
    service().streamAnswer(42L, "비동기란?", 7L, approved, emitter);

    assertThat(emitter.events).anyMatch(e -> e.contains("references"));
    assertThat(emitter.events).anyMatch(e -> e.contains("token"));
    verify(persistence).saveDone(eq(42L), eq("비동기란?"), eq(7L),
        eq("비동기는 Future입니다."), eq(approved.envelopeJson()), anyString(), eq("MOCK"));
    ArgumentCaptor<MentorInput> input = ArgumentCaptor.forClass(MentorInput.class);
    verify(mentorClient).stream(input.capture(), any());
    assertThat(input.getValue().contextText()).isEqualTo(approved.providerContextJson());
    assertThat(emitter.completed).isTrue();
  }

  @Test
  void providerFailureBeforeFirstTokenPersistsFailureAndEmitsOnlySafeError() throws Exception {
    String empty = "{\"fieldsIncluded\":[],\"content\":{}}";
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    // referenceService.embedQuestion/findByEmbedding·knowledgeService.findByEmbedding은 unstubbed 상태로
    // Mockito 기본값(빈 리스트)을 반환한다 — 이 테스트는 LLM 실패 경로만 검증하므로 별도 stub이 불필요하다.
    doAnswer(inv -> { throw new RuntimeException("llm down current_code=secret"); })
        .when(mentorClient).stream(any(), any());

    RecordingEmitter emitter = new RecordingEmitter();
    service().streamAnswer(42L, "q", null, null, emitter);

    verify(persistence).saveFailed(eq(42L), eq("q"), isNull(), eq(empty), eq("LLM_FAILED"));
    assertThat(emitter.events).anyMatch(e -> e.contains("INTERNAL_ERROR"));
    assertThat(emitter.events).allMatch(e -> !e.contains("current_code") && !e.contains("secret"));
    assertThat(emitter.completed).isTrue();
    assertThat(emitter.error).isNull();
  }

  @Test
  void providerFailureAfterTokenRetainsPartialSseAndPersistsTheExactApprovedEnvelope() {
    MentorSnapshotContext approved = new MentorSnapshotContext(23L,
        "{\"snapshotId\":23}", "{\"fieldsIncluded\":[],\"content\":{}}");
    when(contextAssembler.assemble(approved))
        .thenReturn(new MentorContext(approved.providerContextJson(), approved.envelopeJson(), null));
    doAnswer(inv -> {
      Consumer<String> sink = inv.getArgument(1);
      sink.accept("부분 답변");
      throw new RuntimeException("provider leaked prompt=secret");
    }).when(mentorClient).stream(any(), any());

    RecordingEmitter emitter = new RecordingEmitter();
    service().streamAnswer(42L, "q", 7L, approved, emitter);

    assertThat(emitter.events).anyMatch(e -> e.contains("부분 답변"));
    assertThat(emitter.events).anyMatch(e -> e.contains("INTERNAL_ERROR"));
    assertThat(emitter.events).allMatch(e -> !e.contains("prompt=secret"));
    verify(persistence).saveFailed(42L, "q", 7L, approved.envelopeJson(), "LLM_FAILED");
    assertThat(emitter.completed).isTrue();
  }

  @Test
  void noSnapshotSendsZeroSupplementalContextAndPersistsEmptyObject() {
    String empty = "{\"fieldsIncluded\":[],\"content\":{}}";
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    when(mentorClient.providerName()).thenReturn("MOCK");
    ArgumentCaptor<MentorInput> input = ArgumentCaptor.forClass(MentorInput.class);

    service().streamAnswer(42L, "q", 7L, null, new RecordingEmitter());

    verify(mentorClient).stream(input.capture(), any());
    assertThat(input.getValue().contextText()).isEmpty();
    verify(persistence).saveDone(eq(42L), eq("q"), eq(7L), eq(""), eq(empty),
        anyString(), eq("MOCK"));
  }

  @Test
  void terminalPersistenceFailureStillCompletesWithLastTokenEvidenceAndNoRawError() {
    String empty = "{\"fieldsIncluded\":[],\"content\":{}}";
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    doAnswer(inv -> {
      Consumer<String> sink = inv.getArgument(1);
      sink.accept("마지막 정상 토큰");
      return null;
    }).when(mentorClient).stream(any(), any());
    doThrow(new RuntimeException("db failed snapshot=current_code-secret"))
        .when(persistence).saveDone(any(Long.class), anyString(), any(), anyString(),
            anyString(), anyString(), any());
    doThrow(new RuntimeException("db still failed token=secret"))
        .when(persistence).saveFailed(any(Long.class), anyString(), any(), anyString(), anyString());

    RecordingEmitter emitter = new RecordingEmitter();
    service().streamAnswer(42L, "q", null, null, emitter);

    assertThat(emitter.events).anyMatch(e -> e.contains("마지막 정상 토큰"));
    assertThat(emitter.events).anyMatch(e -> e.contains("mentor response unavailable"));
    assertThat(emitter.events).allMatch(e -> !e.contains("current_code-secret")
        && !e.contains("token=secret"));
    assertThat(emitter.completed).isTrue();
  }

  @Test
  void sseSendAndFailurePersistenceErrorsStillCompleteWithoutLosingPriorTokenEvidence() {
    String empty = "{\"fieldsIncluded\":[],\"content\":{}}";
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    doAnswer(inv -> {
      Consumer<String> sink = inv.getArgument(1);
      sink.accept("전달된 토큰");
      sink.accept("전송 실패 토큰");
      return null;
    }).when(mentorClient).stream(any(), any());
    doThrow(new RuntimeException("db failed raw snapshot"))
        .when(persistence).saveFailed(any(Long.class), anyString(), any(), anyString(), anyString());

    FailingTokenEmitter emitter = new FailingTokenEmitter();
    service().streamAnswer(42L, "q", null, null, emitter);

    assertThat(emitter.events).anyMatch(e -> e.contains("전달된 토큰"));
    assertThat(emitter.events).noneMatch(e -> e.contains("전송 실패 토큰"));
    assertThat(emitter.events).allMatch(e -> !e.contains("raw snapshot")
        && !e.contains("transport secret"));
    assertThat(emitter.completed).isTrue();
  }

  @Test
  void completionFailureAfterDonePersistenceDoesNotRewriteSessionAsFailed() {
    String empty = "{\"fieldsIncluded\":[],\"content\":{}}";
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    when(mentorClient.providerName()).thenReturn("MOCK");

    service().streamAnswer(42L, "q", null, null, new ThrowingCompleteEmitter());

    verify(persistence).saveDone(eq(42L), eq("q"), isNull(), eq(""), eq(empty),
        anyString(), eq("MOCK"));
    verify(persistence, never()).saveFailed(any(Long.class), anyString(), any(), anyString(),
        anyString());
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

  static final class FailingTokenEmitter extends SseEmitter {
    final java.util.List<String> events = new java.util.ArrayList<>();
    boolean completed;

    @Override
    public void send(SseEventBuilder builder) throws java.io.IOException {
      java.util.List<String> next = builder.build().stream()
          .map(d -> String.valueOf(d.getData()))
          .toList();
      if (next.stream().anyMatch(e -> e.contains("전송 실패 토큰"))) {
        throw new java.io.IOException("transport secret=current_code");
      }
      events.addAll(next);
    }

    @Override public void complete() { completed = true; }
  }

  static final class ThrowingCompleteEmitter extends SseEmitter {
    @Override public void send(SseEventBuilder builder) {}

    @Override
    public void complete() {
      throw new IllegalStateException("transport terminal secret");
    }
  }
}
