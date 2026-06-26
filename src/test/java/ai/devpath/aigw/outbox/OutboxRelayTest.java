package ai.devpath.aigw.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** OutboxRelay 단위(learning OutboxRelayFailureTest 미러, mock 기반). */
class OutboxRelayTest {

  @SuppressWarnings("unchecked")
  @Test
  void relaysUnpublishedAndMarksPublished() {
    OutboxRepository repo = mock(OutboxRepository.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    OutboxEntry e = entry("community", "7", "community.seed.ready", "{\"questionId\":7}");
    when(repo.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(e));
    when(kafka.send(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) mock(SendResult.class)));

    OutboxRelay relay = new OutboxRelay(repo, kafka);
    int count = relay.relayOnce();

    assertThat(count).isEqualTo(1);
    assertThat(e.getPublishedAt()).isNotNull();
    verify(kafka).send("community.seed.ready", "7", "{\"questionId\":7}");
    verify(repo).save(e);
  }

  @SuppressWarnings("unchecked")
  @Test
  void sendFailureKeepsUnpublishedAndBreaks() {
    OutboxRepository repo = mock(OutboxRepository.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    OutboxEntry e = entry("community", "8", "community.seed.ready", "{\"questionId\":8}");
    when(repo.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(e));
    when(kafka.send(any(), any(), any())).thenThrow(new RuntimeException("broker down"));

    OutboxRelay relay = new OutboxRelay(repo, kafka);
    int count = relay.relayOnce();

    assertThat(count).isEqualTo(0);
    assertThat(e.getPublishedAt()).isNull();
    verify(repo, never()).save(any());
  }

  private static OutboxEntry entry(String type, String id, String eventType, String payload) {
    OutboxEntry e = new OutboxEntry();
    e.setAggregateType(type);
    e.setAggregateId(id);
    e.setEventType(eventType);
    e.setPayload(payload);
    e.setCreatedAt(Instant.now());
    return e;
  }
}
