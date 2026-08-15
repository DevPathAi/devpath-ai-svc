package ai.devpath.aigw.mentor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-request exactly-once terminal state shared by the deadline callback and provider worker.
 */
final class MentorSessionTerminal {

  private enum Phase { OPEN, TERMINATING, DONE, FAILED }

  private final Object lock = new Object();
  private final MentorPersistenceService persistence;
  private final JsonMapper jsonMapper;
  private final SseEmitter emitter;
  private final long userId;
  private final String question;
  private final Long contentId;
  private final String snapshotJson;
  private final StringBuilder answer = new StringBuilder();

  private Phase phase = Phase.OPEN;
  private String referenceLinksJson = "[]";
  private String provider;
  private Future<?> work;
  private ScheduledFuture<?> deadline;
  private Thread workerThread;
  private boolean cancellationRequested;

  MentorSessionTerminal(MentorPersistenceService persistence, JsonMapper jsonMapper,
      SseEmitter emitter, long userId, String question, Long contentId, String snapshotJson) {
    this.persistence = persistence;
    this.jsonMapper = jsonMapper;
    this.emitter = emitter;
    this.userId = userId;
    this.question = question;
    this.contentId = contentId;
    this.snapshotJson = snapshotJson;
  }

  void workerStarted() {
    synchronized (lock) {
      workerThread = Thread.currentThread();
      ensureOpen();
    }
  }

  void workerFinished() {
    synchronized (lock) {
      if (workerThread == Thread.currentThread()) {
        workerThread = null;
      }
    }
  }

  void attachWork(Future<?> future) {
    boolean cancel;
    synchronized (lock) {
      work = future;
      cancel = cancellationRequested;
    }
    if (cancel) {
      future.cancel(true);
    }
  }

  void attachDeadline(ScheduledFuture<?> future) {
    boolean cancel;
    synchronized (lock) {
      deadline = future;
      cancel = phase != Phase.OPEN;
    }
    if (cancel) {
      future.cancel(false);
    }
  }

  void sendReferences(String json) {
    synchronized (lock) {
      ensureOpen();
      send(SseEmitter.event().name("references").data(json));
      referenceLinksJson = json;
    }
  }

  void sendToken(String token) {
    synchronized (lock) {
      ensureOpen();
      send(SseEmitter.event().name("token").data(token));
      answer.append(token);
    }
  }

  void recordProvider(String value) {
    synchronized (lock) {
      if (phase == Phase.OPEN && value != null && !value.isBlank()) {
        provider = value;
      }
    }
  }

  void throwIfClosed() {
    synchronized (lock) {
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
    Snapshot snapshot;
    Future<?> futureToCancel = null;
    ScheduledFuture<?> deadlineToCancel;
    synchronized (lock) {
      if (phase != Phase.OPEN) {
        return false;
      }
      phase = Phase.TERMINATING;
      cancellationRequested = cancelWork;
      snapshot = new Snapshot(answer.toString(), referenceLinksJson, provider);
      deadlineToCancel = deadline;
      if (cancelWork && workerThread != Thread.currentThread()) {
        futureToCancel = work;
      }
    }

    if (deadlineToCancel != null) {
      deadlineToCancel.cancel(false);
    }
    if (futureToCancel != null) {
      futureToCancel.cancel(true);
    }

    boolean persisted = persist(requestedDone, requestedCode, snapshot);
    boolean effectiveDone = requestedDone && persisted;
    String effectiveCode = persisted ? requestedCode : "PERSISTENCE_FAILED";
    String effectiveMessage = persisted ? safeMessage : "mentor result could not be stored";
    synchronized (lock) {
      phase = effectiveDone ? Phase.DONE : Phase.FAILED;
    }
    sendTerminalBestEffort(effectiveDone, effectiveCode, effectiveMessage);
    completeBestEffort();
    return true;
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
