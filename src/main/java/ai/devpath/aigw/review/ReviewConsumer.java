package ai.devpath.aigw.review;

import ai.devpath.aigw.release.ReleaseJourneyRegistry;
import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * At-least-once Kafka consumer. Outbox ACK-gap redelivery is expected: the durable eventId inbox
 * and session processing lease in ReviewPersistenceService provide the deduplication boundary.
 */
@Component
public class ReviewConsumer {

  private static final Logger log = LoggerFactory.getLogger(ReviewConsumer.class);

  private final ReviewService reviewService;
  private final JsonMapper jsonMapper;
  private final ReleaseJourneyRegistry release;

  public ReviewConsumer(
      ReviewService reviewService,
      JsonMapper jsonMapper,
      ReleaseJourneyRegistry release) {
    this.reviewService = reviewService;
    this.jsonMapper = jsonMapper;
    this.release = release;
  }

  @KafkaListener(topics = SandboxRunSubmittedEvent.EVENT_TYPE, groupId = "devpath-ai-review")
  public void onSandboxRun(String payload) {
    SandboxRunSubmittedEvent event;
    try {
      event = jsonMapper.readValue(payload, SandboxRunSubmittedEvent.class);
    } catch (Exception e) {
      log.warn("SandboxRunSubmittedEvent deserialize failed; payload omitted, bytes={}, error={}",
          payload == null ? 0 : payload.length(), e.getClass().getSimpleName());
      return; // poison 무한재시도 방지
    }
    UUID eventId = ReviewEventIdentity.from(event);
    if (release.holdReview(eventId, event.sandboxSessionId(), event.userId())) {
      throw new ReviewInProgressException(event.sandboxSessionId());
    }
    ReviewDisposition disposition = reviewService.reviewRun(
        eventId,
        event.sandboxSessionId(), event.userId(), event.contentId());
    if (disposition == ReviewDisposition.IN_PROGRESS) {
      throw new ReviewInProgressException(event.sandboxSessionId());
    }
  }
}
