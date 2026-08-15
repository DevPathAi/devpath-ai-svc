package ai.devpath.aigw.mentor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** Admits Mentor work before returning SSE and coordinates cancellation/deadline callbacks. */
@Service
public class MentorExecutionCoordinator {

  private final MentorService mentorService;
  private final MentorPersistenceService persistence;
  private final AsyncTaskExecutor executor;
  private final ScheduledExecutorService scheduler;
  private final MentorTimeoutPolicy timeouts;
  private final JsonMapper jsonMapper;
  private final MentorTerminalIoDispatcher terminalIo;

  public MentorExecutionCoordinator(MentorService mentorService, MentorPersistenceService persistence,
      @Qualifier("mentorExecutor") AsyncTaskExecutor executor,
      @Qualifier("mentorDeadlineScheduler") ScheduledExecutorService scheduler,
      MentorTimeoutPolicy timeouts, JsonMapper jsonMapper,
      MentorTerminalIoDispatcher terminalIo) {
    this.mentorService = mentorService;
    this.persistence = persistence;
    this.executor = executor;
    this.scheduler = scheduler;
    this.timeouts = timeouts;
    this.jsonMapper = jsonMapper;
    this.terminalIo = terminalIo;
  }

  public SseEmitter start(long userId, String question, Long contentId,
      MentorSnapshotContext approvedContext) {
    return start(userId, question, contentId, approvedContext,
        new SseEmitter(timeouts.sseTimeout().toMillis()));
  }

  SseEmitter start(long userId, String question, Long contentId,
      MentorSnapshotContext approvedContext, SseEmitter emitter) {
    String snapshotJson = approvedContext == null
        ? MentorContextAssembler.EMPTY_CONTEXT_JSON : approvedContext.envelopeJson();
    MentorTerminalIoDispatcher.Reservation ioReservation = terminalIo.reserve();
    MentorSessionTerminal terminal = new MentorSessionTerminal(
        persistence, jsonMapper, emitter, userId, question, contentId, snapshotJson,
        ioReservation, approvedContext != null);
    emitter.onTimeout(terminal::timeout);
    emitter.onError(ignored -> terminal.clientAborted());
    emitter.onCompletion(terminal::clientAborted);

    java.util.concurrent.Future<?> work;
    try {
      work = executor.submit(() -> {
        try {
          terminal.workerStarted();
          mentorService.streamAnswer(question, approvedContext, terminal);
        } catch (MentorSessionTerminal.MentorTerminalClosedException ignored) {
          // Deadline/client callback already owns the terminal transition.
        } catch (RuntimeException ignored) {
          terminal.completeFailed("AI_PROVIDER_UNAVAILABLE", "mentor response unavailable");
        } finally {
          terminal.workerFinished();
        }
      });
    } catch (RejectedExecutionException rejected) {
      ioReservation.releaseUnused();
      throw new MentorBusyException();
    }
    terminal.attachWork(work);

    try {
      ScheduledFuture<?> deadline = scheduler.schedule(
          terminal::timeout, timeouts.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
      terminal.attachDeadline(deadline);
      ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
          terminal::heartbeat, timeouts.heartbeatInterval().toNanos(),
          timeouts.heartbeatInterval().toNanos(), TimeUnit.NANOSECONDS);
      terminal.attachHeartbeat(heartbeat);
    } catch (RejectedExecutionException schedulerUnavailable) {
      terminal.completeFailed("AI_PROVIDER_UNAVAILABLE", "mentor response unavailable");
      work.cancel(true);
    }
    return emitter;
  }
}
