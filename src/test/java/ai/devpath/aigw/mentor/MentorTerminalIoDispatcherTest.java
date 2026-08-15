package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MentorTerminalIoDispatcherTest {

  @Test
  void blockedHeartbeatCannotStarveAnotherSessionsTerminalFinalizer() throws Exception {
    ExecutorService terminalLane = Executors.newSingleThreadExecutor();
    ExecutorService terminalTransportLane = Executors.newSingleThreadExecutor();
    ExecutorService heartbeatLane = Executors.newSingleThreadExecutor();
    try {
      MentorTerminalIoDispatcher dispatcher = new MentorTerminalIoDispatcher(
          terminalLane::execute, terminalTransportLane::execute, heartbeatLane::execute, 2);
      MentorTerminalIoDispatcher.Reservation slow = dispatcher.reserve();
      MentorTerminalIoDispatcher.Reservation finishing = dispatcher.reserve();
      CountDownLatch heartbeatEntered = new CountDownLatch(1);
      CountDownLatch releaseHeartbeat = new CountDownLatch(1);
      CountDownLatch terminalFinished = new CountDownLatch(1);

      slow.submitHeartbeat(() -> {
        heartbeatEntered.countDown();
        await(releaseHeartbeat);
      });
      assertThat(heartbeatEntered.await(1, TimeUnit.SECONDS)).isTrue();

      finishing.submitTerminal(terminalFinished::countDown);

      assertThat(terminalFinished.await(500, TimeUnit.MILLISECONDS)).isTrue();
      releaseHeartbeat.countDown();
    } finally {
      terminalLane.shutdownNow();
      terminalTransportLane.shutdownNow();
      heartbeatLane.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
