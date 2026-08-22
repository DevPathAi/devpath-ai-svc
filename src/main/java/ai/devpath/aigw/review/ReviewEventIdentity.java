package ai.devpath.aigw.review;

import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable identity for new outbox events and pre-eventId legacy payloads. */
final class ReviewEventIdentity {

  private ReviewEventIdentity() {}

  static UUID from(SandboxRunSubmittedEvent event) {
    if (event.eventId() != null) {
      return event.eventId();
    }
    return legacy(event.sandboxSessionId());
  }

  static UUID legacy(long sandboxSessionId) {
    String correlation = SandboxRunSubmittedEvent.EVENT_TYPE + ":session:" + sandboxSessionId;
    return UUID.nameUUIDFromBytes(correlation.getBytes(StandardCharsets.UTF_8));
  }
}
