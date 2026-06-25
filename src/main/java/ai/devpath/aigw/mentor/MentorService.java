package ai.devpath.aigw.mentor;

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
  private final AiMentorClient mentorClient;
  private final MentorPersistenceService persistence;
  private final JsonMapper jsonMapper;

  public MentorService(MentorContextAssembler contextAssembler, MentorReferenceService referenceService,
      AiMentorClient mentorClient, MentorPersistenceService persistence, JsonMapper jsonMapper) {
    this.contextAssembler = contextAssembler;
    this.referenceService = referenceService;
    this.mentorClient = mentorClient;
    this.persistence = persistence;
    this.jsonMapper = jsonMapper;
  }

  /** 전용 executor 스레드에서 호출. 예외를 던지지 않고 emitter로 종결한다. */
  public void streamAnswer(long userId, String question, Long contentId, SseEmitter emitter) {
    MentorContext ctx = contextAssembler.assemble(userId, contentId);
    StringBuilder answer = new StringBuilder();
    try {
      List<SimilarContent> refs = referenceService.find(question, ctx.track());
      if (!refs.isEmpty()) {
        emitter.send(SseEmitter.event().name("references").data(jsonMapper.writeValueAsString(refs)));
      }
      mentorClient.stream(new MentorInput(question, ctx.promptText()), token -> {
        answer.append(token);
        try {
          emitter.send(SseEmitter.event().name("token").data(token));
        } catch (IOException io) {
          throw new MentorStreamAbortedException(io); // 클라이언트 끊김 → 스트림 중단
        }
      });
      persistence.saveDone(userId, question, contentId, answer.toString(),
          ctx.snapshotJson(), jsonMapper.writeValueAsString(refs), mentorClient.providerName());
      emitter.complete();
    } catch (MentorStreamAbortedException abort) {
      persistence.saveFailed(userId, question, contentId, ctx.snapshotJson(), "CLIENT_ABORTED");
      emitter.completeWithError(abort.getCause());
    } catch (Exception e) {
      persistence.saveFailed(userId, question, contentId, ctx.snapshotJson(), "LLM_FAILED");
      emitter.completeWithError(e);
    }
  }

  private static final class MentorStreamAbortedException extends RuntimeException {
    MentorStreamAbortedException(Throwable cause) { super(cause); }
  }
}
