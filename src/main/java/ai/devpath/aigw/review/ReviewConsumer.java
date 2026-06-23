package ai.devpath.aigw.review;

import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReviewConsumer {

  private static final Logger log = LoggerFactory.getLogger(ReviewConsumer.class);

  private final ReviewService reviewService;
  private final JsonMapper jsonMapper;

  public ReviewConsumer(ReviewService reviewService, JsonMapper jsonMapper) {
    this.reviewService = reviewService;
    this.jsonMapper = jsonMapper;
  }

  @KafkaListener(topics = SandboxRunSubmittedEvent.EVENT_TYPE, groupId = "devpath-ai-review")
  public void onSandboxRun(String payload) {
    SandboxRunSubmittedEvent event;
    try {
      event = jsonMapper.readValue(payload, SandboxRunSubmittedEvent.class);
    } catch (Exception e) {
      log.warn("SandboxRunSubmittedEvent 역직렬화 실패 — skip: {}", payload, e);
      return; // poison 무한재시도 방지
    }
    reviewService.reviewRun(event.sandboxSessionId(), event.userId(), event.contentId());
  }
}
