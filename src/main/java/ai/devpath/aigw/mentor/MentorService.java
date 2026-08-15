package ai.devpath.aigw.mentor;

import ai.devpath.shared.error.ErrorCode;
import ai.devpath.shared.error.SseSupport;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** 멘토 오케스트레이션(전용 스레드, M-1/M-2): context → references → token 스트림 → 완료 영속. */
@Service
public class MentorService {

  private final MentorContextAssembler contextAssembler;
  private final MentorReferenceService referenceService;
  private final KnowledgeReferenceService knowledgeService;
  private final AiMentorClient mentorClient;
  private final MentorPersistenceService persistence;
  private final JsonMapper jsonMapper;

  public MentorService(MentorContextAssembler contextAssembler, MentorReferenceService referenceService,
      KnowledgeReferenceService knowledgeService,
      AiMentorClient mentorClient, MentorPersistenceService persistence, JsonMapper jsonMapper) {
    this.contextAssembler = contextAssembler;
    this.referenceService = referenceService;
    this.knowledgeService = knowledgeService;
    this.mentorClient = mentorClient;
    this.persistence = persistence;
    this.jsonMapper = jsonMapper;
  }

  /** 전용 executor 스레드에서 호출. 예외를 던지지 않고 emitter로 종결한다. */
  public void streamAnswer(long userId, String question, Long contentId,
      MentorSnapshotContext approvedContext, SseEmitter emitter) {
    MentorContext ctx = contextAssembler.assemble(approvedContext);
    StringBuilder answer = new StringBuilder();
    try {
      // 질문 임베딩은 한 번만 계산해 references 검색과 지식베이스 검색 양쪽에 재사용한다.
      // (리뷰 Important #2: 이전엔 두 서비스가 각자 embed를 호출해 요청당 Ollama embed가 2회였다.)
      List<Double> embedding;
      try {
        embedding = referenceService.embedQuestion(question);
      } catch (RuntimeException e) {
        embedding = null; // 임베딩 실패 → 아래 두 검색 모두 생략, 토큰 스트림은 무관 진행
      }

      List<SimilarContent> refs = embedding == null
          ? List.of() : referenceService.findByEmbedding(embedding, ctx.track());
      if (!refs.isEmpty()) {
        emitter.send(SseEmitter.event().name("references").data(jsonMapper.writeValueAsString(refs)));
      }
      // 지식베이스 근거는 프롬프트에만 넣는다. 비공개 문서라 학습자가 열 수 없으므로
      // SSE references 목록에는 노출하지 않는다.
      List<KnowledgeChunk> referenceDocs = embedding == null
          ? List.of() : knowledgeService.findByEmbedding(embedding);
      mentorClient.stream(new MentorInput(question, ctx.promptText(), referenceDocs), token -> {
        answer.append(token);
        try {
          emitter.send(SseEmitter.event().name("token").data(token));
        } catch (IOException io) {
          throw new MentorStreamAbortedException(io); // 클라이언트 끊김 → 스트림 중단
        }
      });
      persistence.saveDone(userId, question, contentId, answer.toString(),
          ctx.snapshotJson(), jsonMapper.writeValueAsString(refs), mentorClient.providerName());
      completeBestEffort(emitter);
    } catch (MentorStreamAbortedException abort) {
      saveFailedBestEffort(userId, question, contentId, ctx.snapshotJson(), "CLIENT_ABORTED");
      finishWithSafeError(emitter, "stream aborted");
    } catch (Exception e) {
      saveFailedBestEffort(userId, question, contentId, ctx.snapshotJson(), "LLM_FAILED");
      finishWithSafeError(emitter, "mentor response unavailable");
    }
  }

  private void saveFailedBestEffort(long userId, String question, Long contentId,
      String snapshotJson, String errorCode) {
    try {
      persistence.saveFailed(userId, question, contentId, snapshotJson, errorCode);
    } catch (RuntimeException ignored) {
      // 영속 계층 장애가 SSE 종결을 막거나 raw 예외를 사용자 경계로 노출하면 안 된다.
    }
  }

  private void finishWithSafeError(SseEmitter emitter, String safeMessage) {
    try {
      SseSupport.sendError(emitter, ErrorCode.INTERNAL_ERROR, safeMessage);
    } catch (RuntimeException ignored) {
      // 전송이 이미 끊긴 경우에도 complete를 최종 시도한다.
    } finally {
      completeBestEffort(emitter);
    }
  }

  private void completeBestEffort(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (RuntimeException ignored) {
      // SseEmitter 자체가 이미 terminal이면 완료된 상태 전이를 되돌리거나 예외를 노출하지 않는다.
    }
  }

  private static final class MentorStreamAbortedException extends RuntimeException {
    MentorStreamAbortedException(Throwable cause) { super(cause); }
  }
}
