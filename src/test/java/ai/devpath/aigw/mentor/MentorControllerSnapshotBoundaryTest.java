package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MentorControllerSnapshotBoundaryTest {

  private final MentorExecutionCoordinator coordinator = mock(MentorExecutionCoordinator.class);
  private final MentorSnapshotClient snapshotClient = mock(MentorSnapshotClient.class);
  private final MentorController controller = new MentorController(coordinator, snapshotClient, true);

  @Test
  void invalidSnapshotStopsBeforeEmitterExecutorAndProviderWork() {
    when(snapshotClient.consume(23L, "final-jwt"))
        .thenThrow(new MentorSnapshotUnavailableException());

    assertThatThrownBy(() -> controller.sessions(jwt(), new MentorRequest("질문", 7L, 23L)))
        .isInstanceOf(MentorSnapshotUnavailableException.class)
        .hasMessage("mentor snapshot unavailable");

    verify(snapshotClient).consume(23L, "final-jwt");
    verifyNoInteractions(coordinator);
  }

  @Test
  void noSnapshotMakesZeroLcsCallsAndStartsWithZeroSupplementalContext() {
    SseEmitter expected = new SseEmitter();
    when(coordinator.start(42L, "질문", 7L, null)).thenReturn(expected);

    SseEmitter emitter = controller.sessions(jwt(), new MentorRequest("질문", 7L, null));

    verifyNoInteractions(snapshotClient);
    verify(coordinator).start(42L, "질문", 7L, null);
    assertThat(emitter).isSameAs(expected);
  }

  @Test
  void approvedSnapshotIsResolvedSynchronouslyAndPassedUnchanged() {
    MentorSnapshotContext approved =
        new MentorSnapshotContext(23L, "{\"snapshotId\":23}",
            "{\"fieldsIncluded\":[],\"content\":{}}");
    when(snapshotClient.consume(23L, "final-jwt")).thenReturn(approved);
    SseEmitter expected = new SseEmitter();
    when(coordinator.start(42L, "질문", 7L, approved)).thenReturn(expected);

    SseEmitter emitter = controller.sessions(jwt(), new MentorRequest("질문", 7L, 23L));

    verify(snapshotClient).consume(23L, "final-jwt");
    verify(coordinator).start(42L, "질문", 7L, approved);
    assertThat(emitter).isSameAs(expected);
  }

  @Test
  void capacityRejectionRemainsPreSseAndUsesTheDedicatedBusyCode() {
    when(coordinator.start(42L, "질문", null, null)).thenThrow(new MentorBusyException());

    assertThatThrownBy(() -> controller.sessions(jwt(), new MentorRequest("질문", null, null)))
        .isInstanceOf(MentorBusyException.class)
        .satisfies(failure -> assertThat(((MentorBusyException) failure).code().name())
            .isEqualTo("MENTOR_BUSY"))
        .hasMessage("mentor is busy; retry later");

    verifyNoInteractions(snapshotClient);
  }

  private Jwt jwt() {
    return Jwt.withTokenValue("final-jwt")
        .header("alg", "none")
        .subject("42")
        .build();
  }
}
