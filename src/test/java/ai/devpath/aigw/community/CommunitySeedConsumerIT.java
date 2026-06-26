package ai.devpath.aigw.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.outbox.OutboxEntry;
import ai.devpath.aigw.outbox.OutboxRepository;
import ai.devpath.aigw.ollama.OllamaClient;
import ai.devpath.aigw.ollama.dto.EmbedResponse;
import ai.devpath.shared.event.CommunityQuestionPostedEvent;
import ai.devpath.shared.event.CommunitySeedReadyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 왕복 끝단 IT(EmbeddedKafka): community.question.posted 발행 → consume → service →
 * community.seed.ready Outbox 적재(DONE). provider=mock(기본), OllamaClient는 @MockitoBean.
 * OutboxRelayScheduler는 @Profile("!test")라 test 컨텍스트서 비활성 → outbox 적재만 검증.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(topics = {"community.question.posted", "community.seed.ready"}, partitions = 1)
class CommunitySeedConsumerIT {

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper jsonMapper;
  @Autowired OutboxRepository outbox;
  @MockitoBean OllamaClient ollamaClient;

  private static List<Double> emb768() {
    return IntStream.range(0, 768).mapToObj(i -> 0.1).toList();
  }

  @Test
  void consumesQuestionPostedAndPublishesSeedReadyDone() throws Exception {
    long questionId = System.nanoTime();
    when(ollamaClient.embed(anyList())).thenReturn(new EmbedResponse(List.of(emb768())));
    var event = new CommunityQuestionPostedEvent(UUID.randomUUID(), Instant.now(), 42L,
        questionId, questionId, "비동기란?", "Future가 헷갈립니다.");

    kafka.send("community.question.posted", jsonMapper.writeValueAsString(event));

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
        assertThat(findSeedReady(questionId)).isPresent());

    OutboxEntry entry = findSeedReady(questionId).orElseThrow();
    CommunitySeedReadyEvent ready =
        jsonMapper.readValue(entry.getPayload(), CommunitySeedReadyEvent.class);
    assertThat(ready.status()).isEqualTo("DONE");
    assertThat(ready.questionId()).isEqualTo(questionId);
    assertThat(ready.content()).isNotBlank();
    assertThat(ready.provider()).isEqualTo("MOCK");
    assertThat(ready.questionEmbedding()).hasSize(768);

    cleanup(questionId);
  }

  @Test
  void duplicateQuestionPostedHandledConsistently() throws Exception {
    long questionId = System.nanoTime();
    when(ollamaClient.embed(anyList())).thenReturn(new EmbedResponse(List.of(emb768())));
    var event = new CommunityQuestionPostedEvent(UUID.randomUUID(), Instant.now(), 42L,
        questionId, questionId, "재전달?", "중복 이벤트.");
    String payload = jsonMapper.writeValueAsString(event);

    kafka.send("community.question.posted", payload);
    kafka.send("community.question.posted", payload);

    // ai-svc는 무상태 생성이라 중복 생성 가능 → 최소 1건 DONE, 예외 없이 일관 처리(D-6).
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
        assertThat(countSeedReady(questionId)).isGreaterThanOrEqualTo(1L));
    for (OutboxEntry e : outbox.findAll()) {
      if (isFor(e, questionId)) {
        CommunitySeedReadyEvent ready =
            jsonMapper.readValue(e.getPayload(), CommunitySeedReadyEvent.class);
        assertThat(ready.status()).isEqualTo("DONE");
      }
    }
    cleanup(questionId);
  }

  private java.util.Optional<OutboxEntry> findSeedReady(long questionId) {
    return outbox.findAll().stream().filter(e -> isFor(e, questionId)).findFirst();
  }

  private long countSeedReady(long questionId) {
    return outbox.findAll().stream().filter(e -> isFor(e, questionId)).count();
  }

  private static boolean isFor(OutboxEntry e, long questionId) {
    return CommunitySeedReadyEvent.EVENT_TYPE.equals(e.getEventType())
        && String.valueOf(questionId).equals(e.getAggregateId());
  }

  private void cleanup(long questionId) {
    outbox.findAll().stream().filter(e -> isFor(e, questionId)).forEach(outbox::delete);
  }
}
