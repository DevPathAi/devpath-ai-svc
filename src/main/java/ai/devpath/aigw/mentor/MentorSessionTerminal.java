package ai.devpath.aigw.mentor;

import ai.devpath.shared.error.ErrorCode;
import ai.devpath.shared.error.SseSupport;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** Per-request exactly-once terminal state shared by provider, deadline, and transport callbacks. */
final class MentorSessionTerminal {

  private enum Phase { OPEN, TERMINATING, DONE, FAILED }

  private final Object stateLock = new Object();
  private final ReentrantLock transportLock = new ReentrantLock();
  private final Condition transportIdle = transportLock.newCondition();
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
  private boolean transportInProgress;

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
    beginOpenTransport();
    try {
      send(SseEmitter.event().name("references").data(json));
      synchronized (stateLock) {
        if (phase == Phase.OPEN) {
          referenceLinksJson = json;
        }
      }
    } finally {
      endTransport();
    }
  }

  void sendToken(String token) {
    beginOpenTransport();
    try {
      send(SseEmitter.event().name("token").data(token));
      synchronized (stateLock) {
        if (phase == Phase.OPEN) {
          answer.append(token);
        }
      }
    } finally {
      endTransport();
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
    Snapshot snapshot;
    synchronized (stateLock) {
      if (phase != Phase.OPEN) {
        return false;
      }
      phase = Phase.TERMINATING;
      snapshot = new Snapshot(answer.toString(), referenceLinksJson, provider);
      cancellationRequested = cancelWork;
      deadlineToCancel = deadline;
      heartbeatToCancel = heartbeat;
      if (cancelWork && workerThread != Thread.currentThread()) {
        futureToCancel = work;
      }
    }
    signalTransportWaiters();

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
      io.submitTerminal(
          () -> finalizeTerminal(requestedDone, requestedCode, safeMessage, snapshot));
    } catch (RejectedExecutionException unavailable) {
      synchronized (stateLock) {
        phase = Phase.FAILED;
      }
    }
    return true;
  }

  private void finalizeTerminal(boolean requestedDone, String requestedCode, String safeMessage,
      Snapshot snapshot) {
    boolean persisted = persist(requestedDone, requestedCode, snapshot);
    boolean effectiveDone = requestedDone && persisted;
    String effectiveCode = persisted ? requestedCode : "PERSISTENCE_FAILED";
    String effectiveMessage = persisted ? safeMessage : "mentor result could not be stored";
    synchronized (stateLock) {
      phase = effectiveDone ? Phase.DONE : Phase.FAILED;
    }
    io.submitTerminalTransport(
        () -> writeTerminal(effectiveDone, effectiveCode, effectiveMessage));
  }

  private void writeHeartbeat() {
    if (!beginHeartbeatTransport()) {
      return;
    }
    boolean failed = false;
    try {
      try {
        emitter.send(SseEmitter.event().comment("keepalive"));
      } catch (IOException | RuntimeException transportFailure) {
        failed = true;
      }
    } finally {
      endTransport();
    }
    if (failed) {
      clientAborted();
    }
  }

  private void writeTerminal(boolean done, String code, String safeMessage) {
    if (!beginTerminalTransport()) {
      return;
    }
    try {
      sendTerminalBestEffort(done, code, safeMessage);
      completeBestEffort();
    } finally {
      endTransport();
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

  private void beginOpenTransport() {
    try {
      transportLock.lockInterruptibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MentorTerminalClosedException();
    }
    try {
      while (transportInProgress) {
        ensureOpenState();
        try {
          transportIdle.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new MentorTerminalClosedException();
        }
      }
      ensureOpenState();
      transportInProgress = true;
    } finally {
      transportLock.unlock();
    }
  }

  private boolean beginHeartbeatTransport() {
    try {
      transportLock.lockInterruptibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
    try {
      while (transportInProgress) {
        if (!isOpen()) {
          return false;
        }
        try {
          transportIdle.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      if (!isOpen()) {
        return false;
      }
      transportInProgress = true;
      return true;
    } finally {
      transportLock.unlock();
    }
  }

  private boolean beginTerminalTransport() {
    try {
      transportLock.lockInterruptibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
    try {
      while (transportInProgress) {
        try {
          transportIdle.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      transportInProgress = true;
      return true;
    } finally {
      transportLock.unlock();
    }
  }

  private void endTransport() {
    transportLock.lock();
    try {
      transportInProgress = false;
      transportIdle.signalAll();
    } finally {
      transportLock.unlock();
    }
  }

  private void signalTransportWaiters() {
    transportLock.lock();
    try {
      transportIdle.signalAll();
    } finally {
      transportLock.unlock();
    }
  }

  private void ensureOpenState() {
    synchronized (stateLock) {
      ensureOpen();
    }
  }

  private boolean isOpen() {
    synchronized (stateLock) {
      return phase == Phase.OPEN;
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
