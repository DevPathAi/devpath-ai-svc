package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MentorControllerSnapshotBoundaryTest {

  private final MentorService service = mock(MentorService.class);
  private final MentorSnapshotClient snapshotClient = mock(MentorSnapshotClient.class);
  private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
  private final MentorController controller =
      new MentorController(service, snapshotClient, executor, true, Duration.ofSeconds(60));

  @Test
  void invalidSnapshotStopsBeforeEmitterExecutorAndProviderWork() {
    org.mockito.Mockito.when(snapshotClient.consume(23L, "final-jwt"))
        .thenThrow(new MentorSnapshotUnavailableException());

    assertThatThrownBy(() -> controller.sessions(jwt(), new MentorRequest("질문", 7L, 23L)))
        .isInstanceOf(MentorSnapshotUnavailableException.class)
        .hasMessage("mentor snapshot unavailable");

    verify(snapshotClient).consume(23L, "final-jwt");
    verifyNoInteractions(executor, service);
  }

  @Test
  void noSnapshotMakesZeroLcsCallsAndStartsWithZeroSupplementalContext() {
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

    SseEmitter emitter = controller.sessions(jwt(), new MentorRequest("질문", 7L, null));

    verifyNoInteractions(snapshotClient);
    verify(executor).execute(task.capture());
    task.getValue().run();
    verify(service).streamAnswer(42L, "질문", 7L, null, emitter);
    assertThat(emitter).isNotNull();
  }

  @Test
  void approvedSnapshotIsResolvedSynchronouslyAndPassedUnchanged() {
    MentorSnapshotContext approved =
        new MentorSnapshotContext(23L, "{\"snapshotId\":23}", "{\"fieldsIncluded\":[],\"content\":{}}");
    org.mockito.Mockito.when(snapshotClient.consume(23L, "final-jwt")).thenReturn(approved);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

    SseEmitter emitter = controller.sessions(jwt(), new MentorRequest("질문", 7L, 23L));

    verify(snapshotClient).consume(23L, "final-jwt");
    verify(executor).execute(task.capture());
    task.getValue().run();
    verify(service).streamAnswer(42L, "질문", 7L, approved, emitter);
  }

  private Jwt jwt() {
    return Jwt.withTokenValue("final-jwt")
        .header("alg", "none")
        .subject("42")
        .build();
  }
}
