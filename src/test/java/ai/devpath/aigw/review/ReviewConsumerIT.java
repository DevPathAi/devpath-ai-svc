package ai.devpath.aigw.review;

import static org.awaitility.Awaitility.await;

import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(topics = "sandbox.run.submitted", partitions = 1)
class ReviewConsumerIT {

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper jsonMapper;
  @Autowired AiCodeReviewRepository reviews;
  @MockitoBean SandboxClient sandboxClient;

  @Test
  void consumesEventAndPersistsReview() throws Exception {
    long sid = System.nanoTime();
    when(sandboxClient.getSession(anyLong())).thenReturn(new SandboxSessionView(
        sid, 42L, "PYTHON", null, "print(1)", "1\n", "", 0, "COMPLETED"));
    var event = new SandboxRunSubmittedEvent(UUID.randomUUID(), Instant.now(), 42L, sid, "PYTHON", null);

    kafka.send("sandbox.run.submitted", jsonMapper.writeValueAsString(event));

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
        org.assertj.core.api.Assertions.assertThat(reviews.findBySandboxSessionId(sid)).isPresent());
    reviews.findBySandboxSessionId(sid).ifPresent(reviews::delete);
  }
}
