package ai.devpath.aigw.community;

import ai.devpath.aigw.outbox.OutboxEntry;
import ai.devpath.aigw.outbox.OutboxRepository;
import ai.devpath.shared.event.CommunitySeedReadyEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * community.seed.ready를 Transactional Outbox로 발행(learning AssessmentEventPublisher 미러).
 * aggregateType=community, aggregateId=questionId. relay가 토픽=eventType, key=aggregateId로 발행.
 */
@Component
public class CommunitySeedEventPublisher {

  private final OutboxRepository outbox;
  private final JsonMapper jsonMapper;

  public CommunitySeedEventPublisher(OutboxRepository outbox, JsonMapper jsonMapper) {
    this.outbox = outbox;
    this.jsonMapper = jsonMapper;
  }

  @Transactional
  public void publishDone(long questionId, String content, String provider, List<Double> embedding) {
    save(new CommunitySeedReadyEvent(UUID.randomUUID(), Instant.now(), questionId,
        "DONE", content, provider, embedding, null));
  }

  @Transactional
  public void publishFailed(long questionId, String errorCode, String provider, List<Double> embedding) {
    save(new CommunitySeedReadyEvent(UUID.randomUUID(), Instant.now(), questionId,
        "FAILED", null, provider, embedding, errorCode));
  }

  private void save(CommunitySeedReadyEvent event) {
    OutboxEntry entry = new OutboxEntry();
    entry.setAggregateType("community");
    entry.setAggregateId(String.valueOf(event.questionId()));
    entry.setEventType(CommunitySeedReadyEvent.EVENT_TYPE);
    entry.setPayload(serialize(event));
    entry.setCreatedAt(Instant.now());
    outbox.save(entry);
  }

  private String serialize(CommunitySeedReadyEvent event) {
    try {
      return jsonMapper.writeValueAsString(event);
    } catch (Exception e) {
      throw new IllegalStateException("CommunitySeedReadyEvent 직렬화 실패", e);
    }
  }
}
