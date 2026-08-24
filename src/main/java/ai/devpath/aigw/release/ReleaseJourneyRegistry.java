package ai.devpath.aigw.release;

import ai.devpath.aigw.mentor.MentorInput;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Staging-only, candidate/run/owner-bound fault state containing no raw user payloads. */
@Component
public class ReleaseJourneyRegistry {
  private static final Pattern CANDIDATE = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern RUN_KEY = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final int MAX_RUNS = 64;

  private final boolean enabled;
  private final JsonMapper jsonMapper;
  private final Map<Key, Observation> runs = new ConcurrentHashMap<>();

  public ReleaseJourneyRegistry(
      @Value("${devpath.release.enabled:false}") boolean enabled,
      JsonMapper jsonMapper) {
    this.enabled = enabled;
    this.jsonMapper = jsonMapper;
  }

  public void arm(String candidate, String runKey, long userId, String command) {
    requireEnabled();
    if (userId <= 0) throw new IllegalArgumentException("release fixture user id is invalid");
    Observation observation = state(requireKey(candidate, runKey));
    synchronized (observation) {
      bindOwner(observation, userId);
      switch (command) {
        case "fail-next-review" -> observation.reviewArmed = true;
        case "fail-next-mentor" -> observation.mentorArmed = true;
        default -> throw new IllegalArgumentException("unsupported AI release fault");
      }
    }
  }

  /** Kafka payloads do not carry browser headers, so exactly one owner-bound armed run may match. */
  public Optional<ReviewFault> consumeReview(
      long userId, UUID eventId, long sessionId, Long contentId) {
    if (!enabled || userId <= 0 || eventId == null || sessionId <= 0) return Optional.empty();
    List<Map.Entry<Key, Observation>> matches = new ArrayList<>();
    for (var entry : runs.entrySet()) {
      Observation observation = entry.getValue();
      synchronized (observation) {
        if (observation.userId == userId && observation.reviewArmed) matches.add(entry);
      }
    }
    if (matches.size() > 1) {
      throw new IllegalStateException("multiple AI release review faults are armed for one owner");
    }
    if (matches.isEmpty()) return Optional.empty();
    var entry = matches.getFirst();
    Observation observation = entry.getValue();
    synchronized (observation) {
      if (!observation.reviewArmed || observation.userId != userId) return Optional.empty();
      observation.reviewArmed = false;
      ReviewReplay replay = new ReviewReplay(eventId, sessionId, userId, contentId);
      observation.reviewReplay = replay;
      return Optional.of(new ReviewFault(entry.getKey(), replay));
    }
  }

  public void recordReviewFailure(ReviewFault fault) {
    if (fault == null || !enabled) return;
    Observation observation = runs.get(fault.key());
    if (observation == null) return;
    synchronized (observation) {
      if (fault.replay().equals(observation.reviewReplay)) {
        observation.reviewInjected = true;
      }
    }
  }

  public boolean holdReview(UUID eventId, long sessionId, long userId) {
    if (!enabled || eventId == null) return false;
    for (Observation observation : runs.values()) {
      synchronized (observation) {
        ReviewReplay replay = observation.reviewReplay;
        if (replay != null
            && replay.eventId().equals(eventId)
            && replay.sessionId() == sessionId
            && replay.userId() == userId) {
          return observation.reviewInjected
              && !observation.reviewReleased
              && !observation.reviewCompleted;
        }
      }
    }
    return false;
  }

  public void recordReviewCompleted(UUID eventId, long sessionId, long userId) {
    if (!enabled || eventId == null) return;
    for (Observation observation : runs.values()) {
      synchronized (observation) {
        ReviewReplay replay = observation.reviewReplay;
        if (replay != null
            && replay.eventId().equals(eventId)
            && replay.sessionId() == sessionId
            && replay.userId() == userId
            && observation.reviewReleased) {
          observation.reviewCompleted = true;
        }
      }
    }
  }

  public Optional<ReviewReplay> clear(String candidate, String runKey, long userId) {
    requireEnabled();
    Observation observation = requireObservation(requireKey(candidate, runKey));
    synchronized (observation) {
      requireOwner(observation, userId);
      observation.reviewArmed = false;
      observation.mentorArmed = false;
      if (observation.reviewInjected
          && !observation.reviewReleased
          && !observation.reviewCompleted) {
        return Optional.of(observation.reviewReplay);
      }
      return Optional.empty();
    }
  }

  public void recordReviewReleased(ReviewReplay replay) {
    if (!enabled || replay == null) return;
    for (Observation observation : runs.values()) {
      synchronized (observation) {
        if (replay.equals(observation.reviewReplay) && observation.reviewInjected) {
          observation.reviewReleased = true;
          return;
        }
      }
    }
  }

  public MentorAttempt mentorAttempt(
      String candidate, String runKey, long userId, boolean approvedContext) {
    if (!enabled || !valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY) || userId <= 0) {
      return MentorAttempt.NONE;
    }
    Key key = new Key(candidate, runKey);
    Observation observation = runs.get(key);
    if (observation == null) return MentorAttempt.NONE;
    synchronized (observation) {
      if (observation.userId != userId) return MentorAttempt.NONE;
      boolean tracked = observation.mentorArmed || observation.mentorStarted;
      if (!tracked) return MentorAttempt.NONE;
      boolean inject = observation.mentorArmed;
      observation.mentorArmed = false;
      observation.mentorStarted = true;
      observation.mentorApprovedContext |= approvedContext;
      return new MentorAttempt(key, userId, true, inject);
    }
  }

  public void recordMentorPayload(MentorAttempt attempt, MentorInput input) {
    Observation observation = observation(attempt);
    if (observation == null) return;
    String digest = digest(input);
    synchronized (observation) {
      observation.mentorAttempts++;
      if (observation.firstMentorPayloadSha256 == null) {
        observation.firstMentorPayloadSha256 = digest;
      }
      observation.lastMentorPayloadSha256 = digest;
    }
  }

  public void deliverMentorToken(
      MentorAttempt attempt, Consumer<String> destination, String token) {
    destination.accept(token);
    Observation observation = observation(attempt);
    if (observation == null || !attempt.injectFailure()) return;
    synchronized (observation) {
      if (!observation.mentorFaultThrown) {
        observation.mentorFaultThrown = true;
        observation.mentorPartial = token != null && !token.isEmpty();
        throw new ReleaseInjectedMentorException();
      }
    }
  }

  public void recordMentorFailed(MentorAttempt attempt, boolean persisted) {
    Observation observation = observation(attempt);
    if (observation != null && persisted) {
      synchronized (observation) {
        observation.mentorFailed = true;
      }
    }
  }

  public void recordMentorDone(MentorAttempt attempt, boolean persisted) {
    Observation observation = observation(attempt);
    if (observation != null && persisted) {
      synchronized (observation) {
        observation.mentorDone = true;
      }
    }
  }

  public boolean checkpoint(String candidate, String runKey, String checkpoint) {
    if (!enabled || !valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) return false;
    Observation observation = runs.get(new Key(candidate, runKey));
    if (observation == null) return false;
    synchronized (observation) {
      boolean payloadExact = observation.mentorAttempts >= 2
          && observation.firstMentorPayloadSha256 != null
          && observation.firstMentorPayloadSha256.equals(
              observation.lastMentorPayloadSha256);
      return switch (checkpoint) {
        case "partial-review-retains-run-and-review" -> observation.reviewInjected;
        case "kafka-outbox-review-correlated" ->
            observation.reviewReleased && observation.reviewCompleted;
        case "private-mentor-prompt-committed" ->
            observation.mentorApprovedContext && observation.mentorFailed;
        case "mentor-partial-retained" ->
            observation.mentorPartial && observation.mentorFailed;
        case "mentor-provider-payload-exact" -> payloadExact;
        case "mentor-terminal-complete" -> observation.mentorDone;
        case "urls-logs-artifacts-clean" -> observation.mentorAttempts > 0
            && valid(observation.firstMentorPayloadSha256, SHA256)
            && valid(observation.lastMentorPayloadSha256, SHA256);
        case "sensitive-boundaries-clean" ->
            observation.reviewCompleted && observation.mentorApprovedContext
                && payloadExact && observation.mentorDone;
        default -> false;
      };
    }
  }

  public Snapshot snapshot(String candidate, String runKey) {
    Observation observation = requireObservation(requireKey(candidate, runKey));
    synchronized (observation) {
      return new Snapshot(
          observation.userId,
          observation.reviewReplay == null ? null : observation.reviewReplay.eventId(),
          observation.reviewReplay == null ? null : observation.reviewReplay.sessionId(),
          observation.reviewInjected,
          observation.reviewReleased,
          observation.reviewCompleted,
          observation.mentorAttempts,
          observation.firstMentorPayloadSha256,
          observation.lastMentorPayloadSha256,
          observation.mentorApprovedContext,
          observation.mentorPartial,
          observation.mentorFailed,
          observation.mentorDone);
    }
  }

  private Observation observation(MentorAttempt attempt) {
    return !enabled || attempt == null || !attempt.tracked() ? null : runs.get(attempt.key());
  }

  private String digest(MentorInput input) {
    try {
      byte[] canonical = jsonMapper.writeValueAsBytes(input);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (Exception failure) {
      throw new IllegalStateException("mentor release payload digest failed", failure);
    }
  }

  private Observation state(Key key) {
    Observation current = runs.get(key);
    if (current != null) return current;
    if (runs.size() >= MAX_RUNS) {
      throw new IllegalStateException("AI release run capacity is exhausted");
    }
    return runs.computeIfAbsent(key, ignored -> new Observation());
  }

  private Observation requireObservation(Key key) {
    Observation observation = runs.get(key);
    if (observation == null) throw new IllegalStateException("AI release run is not prepared");
    return observation;
  }

  private void requireEnabled() {
    if (!enabled) throw new IllegalStateException("AI release hooks are disabled");
  }

  private static Key requireKey(String candidate, String runKey) {
    if (!valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) {
      throw new IllegalArgumentException("AI release binding is invalid");
    }
    return new Key(candidate, runKey);
  }

  private static void bindOwner(Observation observation, long userId) {
    if (observation.userId == 0) observation.userId = userId;
    requireOwner(observation, userId);
  }

  private static void requireOwner(Observation observation, long userId) {
    if (userId <= 0 || observation.userId != userId) {
      throw new IllegalArgumentException("AI release owner binding does not match");
    }
  }

  private static boolean valid(String value, Pattern pattern) {
    return value != null && pattern.matcher(value).matches();
  }

  private record Key(String candidate, String runKey) {}

  private static final class Observation {
    private long userId;
    private boolean reviewArmed;
    private ReviewReplay reviewReplay;
    private boolean reviewInjected;
    private boolean reviewReleased;
    private boolean reviewCompleted;
    private boolean mentorArmed;
    private boolean mentorStarted;
    private boolean mentorApprovedContext;
    private boolean mentorFaultThrown;
    private int mentorAttempts;
    private String firstMentorPayloadSha256;
    private String lastMentorPayloadSha256;
    private boolean mentorPartial;
    private boolean mentorFailed;
    private boolean mentorDone;
  }

  public record ReviewReplay(UUID eventId, long sessionId, long userId, Long contentId) {}

  public record ReviewFault(Key key, ReviewReplay replay) {}

  public record MentorAttempt(Key key, long userId, boolean tracked, boolean injectFailure) {
    public static final MentorAttempt NONE = new MentorAttempt(null, 0, false, false);
  }

  public record Snapshot(
      long userId,
      UUID reviewEventId,
      Long reviewSessionId,
      boolean reviewInjected,
      boolean reviewReleased,
      boolean reviewCompleted,
      int mentorAttempts,
      String firstMentorPayloadSha256,
      String lastMentorPayloadSha256,
      boolean mentorApprovedContext,
      boolean mentorPartial,
      boolean mentorFailed,
      boolean mentorDone) {}
}
