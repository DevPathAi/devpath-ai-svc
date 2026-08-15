package ai.devpath.aigw.mentor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** Bounded off-scheduler execution for terminal persistence and SSE writes. */
@Component
final class MentorTerminalIoDispatcher {

  static final int MAX_SESSIONS = 48;

  private final TaskExecutor terminalExecutor;
  private final TaskExecutor heartbeatExecutor;
  private final Semaphore reservations;

  @Autowired
  MentorTerminalIoDispatcher(
      @Qualifier("mentorTerminalIoExecutor") TaskExecutor terminalExecutor,
      @Qualifier("mentorHeartbeatIoExecutor") TaskExecutor heartbeatExecutor) {
    this(terminalExecutor, heartbeatExecutor, MAX_SESSIONS);
  }

  MentorTerminalIoDispatcher(TaskExecutor terminalExecutor, TaskExecutor heartbeatExecutor,
      int maxSessions) {
    this.terminalExecutor = terminalExecutor;
    this.heartbeatExecutor = heartbeatExecutor;
    this.reservations = new Semaphore(maxSessions, true);
  }

  Reservation reserve() {
    if (!reservations.tryAcquire()) {
      throw new MentorBusyException();
    }
    return new Reservation();
  }

  static Reservation directReservation() {
    SyncTaskExecutor direct = new SyncTaskExecutor();
    return new MentorTerminalIoDispatcher(direct, direct, 1).reserve();
  }

  final class Reservation {
    private final AtomicBoolean terminalSubmitted = new AtomicBoolean();
    private final AtomicBoolean heartbeatInFlight = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();

    void submitTerminal(Runnable task) {
      if (!terminalSubmitted.compareAndSet(false, true)) {
        return;
      }
      try {
        terminalExecutor.execute(() -> {
          try {
            task.run();
          } finally {
            release();
          }
        });
      } catch (RejectedExecutionException rejected) {
        release();
        throw rejected;
      }
    }

    void submitHeartbeat(Runnable task) {
      if (terminalSubmitted.get() || !heartbeatInFlight.compareAndSet(false, true)) {
        return;
      }
      try {
        heartbeatExecutor.execute(() -> {
          try {
            if (!terminalSubmitted.get()) {
              task.run();
            }
          } finally {
            heartbeatInFlight.set(false);
          }
        });
      } catch (RejectedExecutionException rejected) {
        heartbeatInFlight.set(false);
      }
    }

    void releaseUnused() {
      terminalSubmitted.set(true);
      release();
    }

    private void release() {
      if (released.compareAndSet(false, true)) {
        reservations.release();
      }
    }
  }
}
