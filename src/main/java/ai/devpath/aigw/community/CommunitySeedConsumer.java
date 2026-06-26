package ai.devpath.aigw.community;

import ai.devpath.shared.event.CommunityQuestionPostedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * community.question.posted 컨슈머(review ReviewConsumer 미러). 멱등 — ai-svc는 무상태 생성이라
 * 중복 생성이 가능하나 최종 멱등은 community 측 UNIQUE(question_id)가 보장(설계 D-6).
 */
@Component
public class CommunitySeedConsumer {

  private static final Logger log = LoggerFactory.getLogger(CommunitySeedConsumer.class);

  private final CommunitySeedService seedService;
  private final JsonMapper jsonMapper;

  public CommunitySeedConsumer(CommunitySeedService seedService, JsonMapper jsonMapper) {
    this.seedService = seedService;
    this.jsonMapper = jsonMapper;
  }

  @KafkaListener(topics = CommunityQuestionPostedEvent.EVENT_TYPE, groupId = "devpath-ai-community-seed")
  public void onQuestionPosted(String payload) {
    CommunityQuestionPostedEvent event;
    try {
      event = jsonMapper.readValue(payload, CommunityQuestionPostedEvent.class);
    } catch (Exception e) {
      log.warn("CommunityQuestionPostedEvent 역직렬화 실패 — skip: {}", payload, e);
      return; // poison 무한재시도 방지
    }
    seedService.process(event);
  }
}
