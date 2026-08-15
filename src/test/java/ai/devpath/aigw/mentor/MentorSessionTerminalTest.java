package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.devpath.shared.error.ErrorResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class MentorSessionTerminalTest {

  private final MentorPersistenceService persistence = mock(MentorPersistenceService.class);
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void successPersistsAndEmitsExactlyOnceDespiteLateFailure() {
    RecordingEmitter emitter = new RecordingEmitter();
    MentorSessionTerminal terminal = terminal(emitter);
    terminal.sendToken("보존할 토큰");
    terminal.selectProvider("OLLAMA");

    terminal.completeDone();
    terminal.completeFailed("AI_PROVIDER_UNAVAILABLE", "mentor response unavailable");

    verify(persistence).saveDone(42L, "q", 7L, "보존할 토큰", "{}", "[]", "OLLAMA");
    verify(persistence, never()).saveFailed(any(Long.class), anyString(), any(), anyString(),
        anyString(), anyString(), any(), anyString());
    assertThat(emitter.terminalPayloads()).containsExactly("{\"status\":\"DONE\"}");
    assertThat(emitter.completeCalls).isEqualTo(1);
  }

  @Test
  void timeoutPersistsPartialTokensAndCannotBeOverwrittenByLateDone() {
    RecordingEmitter emitter = new RecordingEmitter();
    MentorSessionTerminal terminal = terminal(emitter);
    terminal.sendToken("부분 답변");

    terminal.timeout();
    assertThat(terminal.completeDone()).isFalse();

    verify(persistence).saveFailed(42L, "q", 7L, "부분 답변", "{}", "[]", null,
        "AI_TIMEOUT");
    verify(persistence, never()).saveDone(any(Long.class), anyString(), any(), anyString(),
        anyString(), anyString(), any());
    assertThat(emitter.terminalPayloads()).singleElement().satisfies(payload -> {
      assertThat(payload).contains("\"status\":\"FAILED\"");
      assertThat(payload).contains("\"code\":\"AI_TIMEOUT\"");
      assertThat(payload).doesNotContain("부분 답변");
    });
  }

  @Test
  void providerSelectionAfterTerminalClaimCannotOverwritePersistedProvider() {
    RecordingEmitter emitter = new RecordingEmitter();
    MentorSessionTerminal terminal = terminal(emitter);
    terminal.selectProvider("PRIMARY");

    terminal.timeout();

    assertThatThrownBy(() -> terminal.selectProvider("FALLBACK"))
        .isInstanceOf(MentorSessionTerminal.MentorTerminalClosedException.class);
    verify(persistence).saveFailed(42L, "q", 7L, "", "{}", "[]", "PRIMARY",
        "AI_TIMEOUT");
    assertThat(emitter.terminalPayloads()).hasSize(1);
  }

  @Test
  void persistenceFailureProducesOneSafeFailedTerminalWithoutSecondDbWrite() {
    doThrow(new RuntimeException("db snapshot=secret token=secret"))
        .when(persistence).saveDone(any(Long.class), anyString(), any(), anyString(),
            anyString(), anyString(), any());
    RecordingEmitter emitter = new RecordingEmitter();
    MentorSessionTerminal terminal = terminal(emitter);
    terminal.sendToken("이미 받은 답변");

    terminal.completeDone();

    verify(persistence).saveDone(eq(42L), eq("q"), eq(7L), eq("이미 받은 답변"), eq("{}"),
        eq("[]"), any());
    verify(persistence, never()).saveFailed(any(Long.class), anyString(), any(), anyString(),
        anyString(), anyString(), any(), anyString());
    assertThat(emitter.terminalPayloads()).singleElement().satisfies(payload -> {
      assertThat(payload).contains("PERSISTENCE_FAILED");
      assertThat(payload).doesNotContain("secret");
    });
  }

  @Test
  void terminalSendAndCompleteFailuresNeverTriggerAnotherPersistenceTransition() {
    FailingTerminalEmitter emitter = new FailingTerminalEmitter();
    MentorSessionTerminal terminal = terminal(emitter);

    terminal.completeFailed("AI_PROVIDER_UNAVAILABLE", "mentor response unavailable");
    terminal.completeDone();

    verify(persistence).saveFailed(42L, "q", 7L, "", "{}", "[]", null,
        "AI_PROVIDER_UNAVAILABLE");
    verify(persistence, never()).saveDone(any(Long.class), anyString(), any(), anyString(),
        anyString(), anyString(), any());
    assertThat(emitter.terminalAttempts).isEqualTo(1);
    assertThat(emitter.completeCalls).isEqualTo(1);
  }

  @Test
  void cancellationThatWinsBeforeFutureAttachmentCancelsTheLateAttachedWork() {
    MentorSessionTerminal terminal = terminal(new RecordingEmitter());
    Future<?> work = mock(Future.class);

    terminal.clientAborted();
    terminal.attachWork(work);

    verify(work).cancel(true);
  }

  @Test
  void legacySuccessCompletesWithEofAndDoesNotEmitV2Terminal() {
    RecordingEmitter emitter = new RecordingEmitter();
    MentorSessionTerminal terminal = legacyTerminal(emitter);
    terminal.sendToken("legacy-token");

    terminal.completeDone();

    assertThat(emitter.data).contains("legacy-token");
    assertThat(emitter.terminalPayloads()).isEmpty();
    assertThat(emitter.errorEnvelopes()).isEmpty();
    assertThat(emitter.completeCalls).isEqualTo(1);
  }

  @Test
  void legacyFailureEmitsExistingErrorEnvelopeWithoutV2Terminal() {
    RecordingEmitter emitter = new RecordingEmitter();
    MentorSessionTerminal terminal = legacyTerminal(emitter);

    terminal.completeFailed("AI_PROVIDER_UNAVAILABLE", "mentor response unavailable");

    assertThat(emitter.terminalPayloads()).isEmpty();
    assertThat(emitter.errorEnvelopes()).singleElement().satisfies(envelope -> {
      assertThat(envelope.error().code()).isEqualTo("INTERNAL_ERROR");
      assertThat(envelope.error().message()).isEqualTo("mentor response unavailable");
    });
    assertThat(emitter.completeCalls).isEqualTo(1);
  }

  private MentorSessionTerminal terminal(SseEmitter emitter) {
    return new MentorSessionTerminal(
        persistence, mapper, emitter, 42L, "q", 7L, "{}", true);
  }

  private MentorSessionTerminal legacyTerminal(SseEmitter emitter) {
    return new MentorSessionTerminal(
        persistence, mapper, emitter, 42L, "q", 7L, "{}", false);
  }

  static class RecordingEmitter extends SseEmitter {
    final List<String> data = new ArrayList<>();
    int completeCalls;

    @Override
    public void send(SseEventBuilder builder) {
      builder.build().forEach(item -> {
        objects.add(item.getData());
        data.add(String.valueOf(item.getData()));
      });
    }

    @Override
    public void complete() {
      completeCalls++;
    }

    List<String> terminalPayloads() {
      return data.stream().filter(value -> value.contains("\"status\":" )).toList();
    }

    List<ErrorResponse> errorEnvelopes() {
      return objects.stream().filter(ErrorResponse.class::isInstance)
          .map(ErrorResponse.class::cast).toList();
    }

    final List<Object> objects = new ArrayList<>();
  }

  static final class FailingTerminalEmitter extends SseEmitter {
    int terminalAttempts;
    int completeCalls;

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      terminalAttempts++;
      throw new IOException("transport raw token=secret");
    }

    @Override
    public void complete() {
      completeCalls++;
      throw new IllegalStateException("complete raw snapshot=secret");
    }
  }
}
