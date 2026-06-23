package ai.devpath.aigw.review;

import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOffWithMaxRetries;
import tools.jackson.databind.json.JsonMapper;

/** 리뷰 컨슈머 에러 핸들러: 일시 오류 지수백오프 재시도(3회), 소진 시 FAILED(LLM_EXHAUSTED). */
@Configuration
public class ReviewKafkaConfig {

  private static final Logger log = LoggerFactory.getLogger(ReviewKafkaConfig.class);

  private final ReviewPersistenceService persistence;
  private final JsonMapper jsonMapper;

  public ReviewKafkaConfig(ReviewPersistenceService persistence, JsonMapper jsonMapper) {
    this.persistence = persistence;
    this.jsonMapper = jsonMapper;
  }

  @Bean
  public DefaultErrorHandler reviewErrorHandler() {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
    backOff.setInitialInterval(1000L);
    backOff.setMultiplier(2.0);
    return new DefaultErrorHandler(this::recover, backOff);
  }

  /** 재시도 소진 시: payload에서 sandboxSessionId 추출 → FAILED(LLM_EXHAUSTED). */
  private void recover(ConsumerRecord<?, ?> record, Exception ex) {
    Object value = record.value();
    try {
      SandboxRunSubmittedEvent event =
          jsonMapper.readValue(String.valueOf(value), SandboxRunSubmittedEvent.class);
      persistence.markExhausted(event.sandboxSessionId());
      log.warn("리뷰 재시도 소진 → FAILED(LLM_EXHAUSTED) session={}", event.sandboxSessionId(), ex);
    } catch (Exception e) {
      log.error("recoverer payload 처리 실패: {}", value, e);
    }
  }
}
