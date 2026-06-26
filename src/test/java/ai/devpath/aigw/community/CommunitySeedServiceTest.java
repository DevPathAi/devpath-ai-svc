package ai.devpath.aigw.community;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.ollama.OllamaClient;
import ai.devpath.aigw.ollama.dto.EmbedResponse;
import ai.devpath.shared.event.CommunityQuestionPostedEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 시드 오케스트레이션 분기: DONE / LLM_FAILED / KILL_SWITCH / 임베딩 best-effort. */
class CommunitySeedServiceTest {

  private final AiSeedClient client = mock(AiSeedClient.class);
  private final OllamaClient ollama = mock(OllamaClient.class);
  private final CommunitySeedEventPublisher publisher = mock(CommunitySeedEventPublisher.class);

  private static List<Double> emb768() {
    return IntStream.range(0, 768).mapToObj(i -> 0.1).toList();
  }

  private static CommunityQuestionPostedEvent event() {
    return new CommunityQuestionPostedEvent(UUID.randomUUID(), Instant.now(), 42L, 7L, 7L,
        "비동기란?", "Future가 헷갈립니다.");
  }

  @Test
  void happyPathPublishesDoneWithAnswerAndEmbedding() {
    when(client.providerName()).thenReturn("MOCK");
    when(client.generate(any())).thenReturn(new SeedAnswer("방향 제시 초안"));
    List<Double> emb = emb768();
    when(ollama.embed(anyList())).thenReturn(new EmbedResponse(List.of(emb)));

    CommunitySeedService service = new CommunitySeedService(client, ollama, publisher, true);
    service.process(event());

    verify(publisher).publishDone(eq(7L), eq("방향 제시 초안"), eq("MOCK"), eq(emb));
    verify(publisher, never()).publishFailed(any(Long.class), any(), any(), any());
  }

  @Test
  void llmFailurePublishesFailedWithBestEffortEmbedding() {
    when(client.providerName()).thenReturn("MOCK");
    when(client.generate(any())).thenThrow(new SeedGenerationException("LLM_FAILED", "boom", null));
    List<Double> emb = emb768();
    when(ollama.embed(anyList())).thenReturn(new EmbedResponse(List.of(emb)));

    CommunitySeedService service = new CommunitySeedService(client, ollama, publisher, true);
    service.process(event());

    verify(publisher).publishFailed(eq(7L), eq("LLM_FAILED"), eq("MOCK"), eq(emb));
    verify(publisher, never()).publishDone(any(Long.class), any(), any(), any());
  }

  @Test
  void killSwitchPublishesFailedWithoutCallingLlmOrEmbed() {
    CommunitySeedService service = new CommunitySeedService(client, ollama, publisher, false);
    service.process(event());

    verify(publisher).publishFailed(eq(7L), eq("KILL_SWITCH"), isNull(), isNull());
    verify(client, never()).generate(any());
    verify(ollama, never()).embed(anyList());
  }

  @Test
  void embeddingFailureStillPublishesDoneWithNullEmbedding() {
    when(client.providerName()).thenReturn("MOCK");
    when(client.generate(any())).thenReturn(new SeedAnswer("초안"));
    when(ollama.embed(anyList())).thenThrow(new RuntimeException("embed down"));

    CommunitySeedService service = new CommunitySeedService(client, ollama, publisher, true);
    service.process(event());

    verify(publisher).publishDone(eq(7L), eq("초안"), eq("MOCK"), isNull());
  }

  @Test
  void unexpectedLlmFailureFallsBackToLlmFailed() {
    when(client.providerName()).thenReturn("MOCK");
    when(client.generate(any())).thenThrow(new RuntimeException("unexpected"));
    when(ollama.embed(anyList())).thenReturn(new EmbedResponse(List.of(emb768())));

    CommunitySeedService service = new CommunitySeedService(client, ollama, publisher, true);
    service.process(event());

    verify(publisher).publishFailed(eq(7L), eq("LLM_FAILED"), eq("MOCK"), anyList());
  }
}
