package ai.devpath.aigw.community;

import ai.devpath.aigw.ollama.OllamaClient;
import ai.devpath.aigw.ollama.dto.EmbedResponse;
import ai.devpath.shared.event.CommunityQuestionPostedEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 커뮤니티 AI 시드 오케스트레이션(설계 §3 D-1/D-4/D-6, §6):
 * kill-switch 선체크 → 임베딩(best-effort) + AiSeedClient 답변 → community.seed.ready Outbox 발행.
 * LLM 실패 → FAILED(LLM_FAILED), kill-switch → FAILED(KILL_SWITCH). 임베딩과 답변은 독립(D-4).
 */
@Service
public class CommunitySeedService {

  private static final Logger log = LoggerFactory.getLogger(CommunitySeedService.class);

  private final AiSeedClient seedClient;
  private final OllamaClient ollamaClient;
  private final CommunitySeedEventPublisher publisher;
  private final boolean enabled;

  public CommunitySeedService(AiSeedClient seedClient, OllamaClient ollamaClient,
      CommunitySeedEventPublisher publisher,
      @Value("${devpath.community-seed.enabled:true}") boolean enabled) {
    this.seedClient = seedClient;
    this.ollamaClient = ollamaClient;
    this.publisher = publisher;
    this.enabled = enabled;
  }

  public void process(CommunityQuestionPostedEvent event) {
    long questionId = event.questionId();
    if (!enabled) {
      publisher.publishFailed(questionId, "KILL_SWITCH", null, null);
      return;
    }
    // 임베딩은 답변과 독립(D-4): 실패해도 답변은 진행, 임베딩만 null(유사검색서 제외).
    List<Double> embedding = tryEmbed(event.title(), event.bodyMd());
    try {
      SeedAnswer answer = seedClient.generate(new SeedInput(event.title(), event.bodyMd()));
      publisher.publishDone(questionId, answer.content(), seedClient.providerName(), embedding);
    } catch (RuntimeException e) {
      String code = e instanceof SeedGenerationException sge ? sge.errorCode() : "LLM_FAILED";
      log.warn("커뮤니티 시드 생성 실패 questionId={} code={}", questionId, code, e);
      publisher.publishFailed(questionId, code, seedClient.providerName(), embedding);
    }
  }

  private List<Double> tryEmbed(String title, String bodyMd) {
    try {
      String text = (title == null ? "" : title) + "\n" + (bodyMd == null ? "" : bodyMd);
      EmbedResponse response = ollamaClient.embed(List.of(text));
      return response.embeddings().get(0);
    } catch (RuntimeException e) {
      log.warn("커뮤니티 시드 임베딩 실패(best-effort, null로 진행)", e);
      return null;
    }
  }
}
