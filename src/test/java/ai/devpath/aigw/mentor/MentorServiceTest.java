package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
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
  private final JsonMapper mapper = JsonMapper.builder().build();

  private MentorService service() {
    return service(mentorClient);
  }

  private MentorService service(AiMentorClient client) {
    return new MentorService(
        contextAssembler, referenceService, knowledgeService, client, mapper);
  }

  @Test
  void streamsReferencesThenTokensThenPersistsDoneAndExplicitTerminal() {
    List<Double> embedding = Collections.nCopies(768, 0.1);
    MentorSnapshotContext approved = new MentorSnapshotContext(23L,
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\","
            + "\"fieldsIncluded\":[\"current_code\"],\"content\":{\"current_code\":\"code\"}}",
        "{\"fieldsIncluded\":[\"current_code\"],\"content\":{\"current_code\":\"code\"}}");
    when(contextAssembler.assemble(approved))
        .thenReturn(new MentorContext(approved.providerContextJson(), approved.envelopeJson(), null));
    when(referenceService.embedQuestion("비동기란?")).thenReturn(embedding);
    when(referenceService.findByEmbedding(embedding, null))
        .thenReturn(List.of(new SimilarContent(1, "a", "t")));
    doAnswer(invocation -> {
      Consumer<String> sink = invocation.getArgument(1);
      Consumer<String> provider = invocation.getArgument(2);
      provider.accept("MOCK");
      sink.accept("비동기는 ");
      sink.accept("Future입니다.");
      return null;
    }).when(mentorClient).stream(any(), any(), any());
    RecordingEmitter emitter = new RecordingEmitter();

    service().streamAnswer("비동기란?", approved,
        terminal(emitter, 42L, "비동기란?", 7L, approved.envelopeJson()));

    verify(persistence).saveDone(eq(42L), eq("비동기란?"), eq(7L),
        eq("비동기는 Future입니다."), eq(approved.envelopeJson()), anyString(), eq("MOCK"));
    ArgumentCaptor<MentorInput> input = ArgumentCaptor.forClass(MentorInput.class);
    verify(mentorClient).stream(input.capture(), any(), any());
    assertThat(input.getValue().contextText()).isEqualTo(approved.providerContextJson());
    assertThat(emitter.data).anyMatch(value -> value.contains("Future입니다."));
    assertThat(emitter.terminalPayloads()).containsExactly("{\"status\":\"DONE\"}");
  }

  @Test
  void providerTimeoutBeforeFirstTokenPersistsOneSafeFailedTerminal() {
    String empty = MentorContextAssembler.EMPTY_CONTEXT_JSON;
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    doAnswer(invocation -> {
      Consumer<String> provider = invocation.getArgument(2);
      provider.accept("OLLAMA");
      throw new RuntimeException(new HttpTimeoutException("raw current_code=secret"));
    }).when(mentorClient).stream(any(), any(), any());
    RecordingEmitter emitter = new RecordingEmitter();

    service().streamAnswer("q", null, terminal(emitter, 42L, "q", null, empty));

    verify(persistence).saveFailed(42L, "q", null, "", empty, "[]", "OLLAMA",
        "AI_TIMEOUT");
    verify(persistence, never()).saveDone(any(Long.class), anyString(), any(), anyString(),
        anyString(), anyString(), any());
    assertThat(emitter.terminalPayloads()).singleElement().satisfies(payload -> {
      assertThat(payload).contains("AI_TIMEOUT");
      assertThat(payload).doesNotContain("current_code", "secret");
    });
  }

  @Test
  void providerTimeoutAfterTokenPreservesOnlyDeliveredPartialAnswer() {
    String empty = MentorContextAssembler.EMPTY_CONTEXT_JSON;
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    doAnswer(invocation -> {
      Consumer<String> sink = invocation.getArgument(1);
      Consumer<String> provider = invocation.getArgument(2);
      provider.accept("CLAUDE");
      sink.accept("부분 답변");
      throw new RuntimeException(new HttpTimeoutException("raw prompt=secret"));
    }).when(mentorClient).stream(any(), any(), any());
    RecordingEmitter emitter = new RecordingEmitter();

    service().streamAnswer("q", null, terminal(emitter, 42L, "q", 7L, empty));

    verify(persistence).saveFailed(42L, "q", 7L, "부분 답변", empty, "[]", "CLAUDE",
        "AI_TIMEOUT");
    assertThat(emitter.data).anyMatch(value -> value.contains("부분 답변"));
    assertThat(emitter.terminalPayloads()).singleElement()
        .satisfies(payload -> assertThat(payload).doesNotContain("prompt=secret"));
  }

  @Test
  void clientAbortPreservesEarlierTokensButNotTheTokenWhoseSendFailed() {
    String empty = MentorContextAssembler.EMPTY_CONTEXT_JSON;
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    doAnswer(invocation -> {
      Consumer<String> sink = invocation.getArgument(1);
      Consumer<String> provider = invocation.getArgument(2);
      provider.accept("OLLAMA");
      sink.accept("전달된 토큰");
      sink.accept("전송 실패 토큰");
      return null;
    }).when(mentorClient).stream(any(), any(), any());
    FailingSecondTokenEmitter emitter = new FailingSecondTokenEmitter();

    service().streamAnswer("q", null, terminal(emitter, 42L, "q", null, empty));

    verify(persistence).saveFailed(42L, "q", null, "전달된 토큰", empty, "[]", "OLLAMA",
        "CLIENT_ABORTED");
    assertThat(emitter.data).anyMatch(value -> value.contains("전달된 토큰"));
    assertThat(emitter.data).noneMatch(value -> value.contains("전송 실패 토큰"));
  }

  @Test
  void noSnapshotSendsZeroSupplementalContextAndPersistsCanonicalEmptyEnvelope() {
    String empty = MentorContextAssembler.EMPTY_CONTEXT_JSON;
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    doAnswer(invocation -> {
      Consumer<String> provider = invocation.getArgument(2);
      provider.accept("MOCK");
      return null;
    }).when(mentorClient).stream(any(), any(), any());
    ArgumentCaptor<MentorInput> input = ArgumentCaptor.forClass(MentorInput.class);

    service().streamAnswer("q", null,
        terminal(new RecordingEmitter(), 42L, "q", 7L, empty));

    verify(mentorClient).stream(input.capture(), any(), any());
    assertThat(input.getValue().contextText()).isEmpty();
    verify(persistence).saveDone(eq(42L), eq("q"), eq(7L), eq(""), eq(empty),
        eq("[]"), eq("MOCK"));
    verify(persistence, never()).saveFailed(any(Long.class), anyString(), any(), anyString(),
        anyString(), anyString(), any(), anyString());
  }

  @Test
  void fallbackPartialFailurePersistsTheActualFallbackProvider() {
    String empty = MentorContextAssembler.EMPTY_CONTEXT_JSON;
    when(contextAssembler.assemble(null)).thenReturn(new MentorContext("", empty, null));
    AiMentorClient primary = client("PRIMARY", (input, sink) -> {
      throw new RuntimeException("primary unavailable");
    });
    AiMentorClient fallback = client("FALLBACK", (input, sink) -> {
      sink.accept("fallback partial");
      throw new RuntimeException("fallback interrupted");
    });

    service(new FallbackMentorClient(List.of(primary, fallback))).streamAnswer(
        "q", null, terminal(new RecordingEmitter(), 42L, "q", 7L, empty));

    verify(persistence).saveFailed(42L, "q", 7L, "fallback partial", empty, "[]",
        "FALLBACK", "AI_PROVIDER_UNAVAILABLE");
  }

  private static AiMentorClient client(String provider, StreamBehavior behavior) {
    return new AiMentorClient() {
      @Override public void stream(MentorInput input, Consumer<String> tokenSink) {
        behavior.stream(input, tokenSink);
      }

      @Override public String providerName() {
        return provider;
      }
    };
  }

  @FunctionalInterface
  private interface StreamBehavior {
    void stream(MentorInput input, Consumer<String> tokenSink);
  }

  private MentorSessionTerminal terminal(SseEmitter emitter, long userId, String question,
      Long contentId, String snapshotJson) {
    return new MentorSessionTerminal(
        persistence, mapper, emitter, userId, question, contentId, snapshotJson, true);
  }

  static class RecordingEmitter extends SseEmitter {
    final List<String> data = new ArrayList<>();

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      builder.build().forEach(item -> data.add(String.valueOf(item.getData())));
    }

    @Override public void complete() {}

    List<String> terminalPayloads() {
      return data.stream().filter(value -> value.contains("\"status\":" )).toList();
    }
  }

  static final class FailingSecondTokenEmitter extends RecordingEmitter {
    @Override
    public void send(SseEventBuilder builder) throws IOException {
      List<String> next = builder.build().stream()
          .map(item -> String.valueOf(item.getData()))
          .toList();
      if (next.stream().anyMatch(value -> value.contains("전송 실패 토큰"))) {
        throw new IOException("transport current_code=secret");
      }
      data.addAll(next);
    }
  }
}
