package ai.devpath.aigw.mentor;

import ai.devpath.shared.error.ErrorCode;
import ai.devpath.shared.error.SseSupport;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** Per-request exactly-once terminal state shared by provider, deadline, and transport callbacks. */
final class MentorSessionTerminal {

  private enum Phase { OPEN, TERMINATING, DONE, FAILED }

  private final Object stateLock = new Object();
  private final ReentrantLock writeLock = new ReentrantLock();
  private final MentorPersistenceService persistence;
  private final JsonMapper jsonMapper;
  private final SseEmitter emitter;
  private final long userId;
  private final String question;
  private final Long contentId;
  private final String snapshotJson;
  private final MentorTerminalIoDispatcher.Reservation io;
  private final boolean explicitTerminal;
  private final StringBuilder answer = new StringBuilder();

  private Phase phase = Phase.OPEN;
  private String referenceLinksJson = "[]";
  private String provider;
  private Future<?> work;
  private ScheduledFuture<?> deadline;
  private ScheduledFuture<?> heartbeat;
  private Thread workerThread;
  private boolean cancellationRequested;

  MentorSessionTerminal(MentorPersistenceService persistence, JsonMapper jsonMapper,
      SseEmitter emitter, long userId, String question, Long contentId, String snapshotJson) {
    this(persistence, jsonMapper, emitter, userId, question, contentId, snapshotJson,
        MentorTerminalIoDispatcher.directReservation(), true);
  }

  MentorSessionTerminal(MentorPersistenceService persistence, JsonMapper jsonMapper,
      SseEmitter emitter, long userId, String question, Long contentId, String snapshotJson,
      boolean explicitTerminal) {
    this(persistence, jsonMapper, emitter, userId, question, contentId, snapshotJson,
        MentorTerminalIoDispatcher.directReservation(), explicitTerminal);
  }

  MentorSessionTerminal(MentorPersistenceService persistence, JsonMapper jsonMapper,
      SseEmitter emitter, long userId, String question, Long contentId, String snapshotJson,
      MentorTerminalIoDispatcher.Reservation io, boolean explicitTerminal) {
    this.persistence = persistence;
    this.jsonMapper = jsonMapper;
    this.emitter = emitter;
    this.userId = userId;
    this.question = question;
    this.contentId = contentId;
    this.snapshotJson = snapshotJson;
    this.io = io;
    this.explicitTerminal = explicitTerminal;
  }

  void workerStarted() {
    synchronized (stateLock) {
      workerThread = Thread.currentThread();
      ensureOpen();
    }
  }

  void workerFinished() {
    synchronized (stateLock) {
      if (workerThread == Thread.currentThread()) {
        workerThread = null;
      }
    }
  }

  void attachWork(Future<?> future) {
    boolean cancel;
    synchronized (stateLock) {
      work = future;
      cancel = cancellationRequested;
    }
    if (cancel) {
      future.cancel(true);
    }
  }

  void attachDeadline(ScheduledFuture<?> future) {
    boolean cancel;
    synchronized (stateLock) {
      deadline = future;
      cancel = phase != Phase.OPEN;
    }
    if (cancel) {
      future.cancel(false);
    }
  }

  void attachHeartbeat(ScheduledFuture<?> future) {
    boolean cancel;
    synchronized (stateLock) {
      heartbeat = future;
      cancel = phase != Phase.OPEN;
    }
    if (cancel) {
      future.cancel(false);
    }
  }

  void sendReferences(String json) {
    lockWriterInterruptibly();
    try {
      synchronized (stateLock) {
        ensureOpen();
      }
      send(SseEmitter.event().name("references").data(json));
      synchronized (stateLock) {
        referenceLinksJson = json;
      }
    } finally {
      writeLock.unlock();
    }
  }

  void sendToken(String token) {
    lockWriterInterruptibly();
    try {
      synchronized (stateLock) {
        ensureOpen();
      }
      send(SseEmitter.event().name("token").data(token));
      synchronized (stateLock) {
        answer.append(token);
      }
    } finally {
      writeLock.unlock();
    }
  }

  void selectProvider(String value) {
    synchronized (stateLock) {
      ensureOpen();
      if (value != null && !value.isBlank()) {
        provider = value;
      }
    }
  }

  void heartbeat() {
    synchronized (stateLock) {
      if (phase != Phase.OPEN) {
        return;
      }
    }
    io.submitHeartbeat(this::writeHeartbeat);
  }

  void throwIfClosed() {
    synchronized (stateLock) {
      ensureOpen();
    }
  }

  boolean completeDone() {
    return finish(true, null, null, false);
  }

  boolean completeFailed(String code, String safeMessage) {
    return finish(false, code, safeMessage, false);
  }

  boolean timeout() {
    return finish(false, "AI_TIMEOUT", "mentor response timed out", true);
  }

  boolean clientAborted() {
    return finish(false, "CLIENT_ABORTED", "stream aborted", true);
  }

  private boolean finish(boolean requestedDone, String requestedCode, String safeMessage,
      boolean cancelWork) {
    Future<?> futureToCancel = null;
    ScheduledFuture<?> deadlineToCancel;
    ScheduledFuture<?> heartbeatToCancel;
    synchronized (stateLock) {
      if (phase != Phase.OPEN) {
        return false;
      }
      phase = Phase.TERMINATING;
      cancellationRequested = cancelWork;
      deadlineToCancel = deadline;
      heartbeatToCancel = heartbeat;
      if (cancelWork && workerThread != Thread.currentThread()) {
        futureToCancel = work;
      }
    }

    if (deadlineToCancel != null) {
      deadlineToCancel.cancel(false);
    }
    if (heartbeatToCancel != null) {
      heartbeatToCancel.cancel(false);
    }
    if (futureToCancel != null) {
      futureToCancel.cancel(true);
    }

    try {
      io.submitTerminal(() -> finalizeTerminal(requestedDone, requestedCode, safeMessage));
    } catch (RejectedExecutionException unavailable) {
      synchronized (stateLock) {
        phase = Phase.FAILED;
      }
    }
    return true;
  }

  private void finalizeTerminal(boolean requestedDone, String requestedCode, String safeMessage) {
    writeLock.lock();
    try {
      Snapshot snapshot;
      synchronized (stateLock) {
        snapshot = new Snapshot(answer.toString(), referenceLinksJson, provider);
      }
      boolean persisted = persist(requestedDone, requestedCode, snapshot);
      boolean effectiveDone = requestedDone && persisted;
      String effectiveCode = persisted ? requestedCode : "PERSISTENCE_FAILED";
      String effectiveMessage = persisted ? safeMessage : "mentor result could not be stored";
      synchronized (stateLock) {
        phase = effectiveDone ? Phase.DONE : Phase.FAILED;
      }
      sendTerminalBestEffort(effectiveDone, effectiveCode, effectiveMessage);
      completeBestEffort();
    } finally {
      writeLock.unlock();
    }
  }

  private void writeHeartbeat() {
    boolean failed = false;
    writeLock.lock();
    try {
      synchronized (stateLock) {
        if (phase != Phase.OPEN) {
          return;
        }
      }
      try {
        emitter.send(SseEmitter.event().comment("keepalive"));
      } catch (IOException | RuntimeException transportFailure) {
        failed = true;
      }
    } finally {
      writeLock.unlock();
    }
    if (failed) {
      clientAborted();
    }
  }

  private boolean persist(boolean done, String errorCode, Snapshot snapshot) {
    try {
      if (done) {
        persistence.saveDone(userId, question, contentId, snapshot.answer(), snapshotJson,
            snapshot.referenceLinksJson(), snapshot.provider());
      } else {
        persistence.saveFailed(userId, question, contentId, snapshot.answer(), snapshotJson,
            snapshot.referenceLinksJson(), snapshot.provider(), errorCode);
      }
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private void sendTerminalBestEffort(boolean done, String code, String safeMessage) {
    if (!explicitTerminal) {
      if (!done) {
        sendLegacyErrorBestEffort(safeMessage);
      }
      return;
    }
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("status", done ? "DONE" : "FAILED");
    if (!done) {
      payload.put("code", code);
      payload.put("message", safeMessage);
    }
    try {
      emitter.send(SseEmitter.event().name("terminal")
          .data(jsonMapper.writeValueAsString(payload)));
    } catch (IOException | RuntimeException ignored) {
      // The database transition remains authoritative if transport is unavailable.
    }
  }

  private void sendLegacyErrorBestEffort(String safeMessage) {
    try {
      SseSupport.sendError(emitter, ErrorCode.INTERNAL_ERROR, safeMessage);
    } catch (RuntimeException ignored) {
      // Legacy transport failure cannot undo the authoritative database transition.
    }
  }

  private void completeBestEffort() {
    try {
      emitter.complete();
    } catch (RuntimeException ignored) {
      // A completed/broken emitter cannot undo the terminal state.
    }
  }

  private void send(SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | RuntimeException transportFailure) {
      throw new MentorClientDisconnectedException();
    }
  }

  private void lockWriterInterruptibly() {
    try {
      writeLock.lockInterruptibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MentorTerminalClosedException();
    }
  }

  private void ensureOpen() {
    if (phase != Phase.OPEN) {
      throw new MentorTerminalClosedException();
    }
  }

  private record Snapshot(String answer, String referenceLinksJson, String provider) {}

  static final class MentorTerminalClosedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  static final class MentorClientDisconnectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
