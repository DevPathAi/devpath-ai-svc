package ai.devpath.aigw.mentor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 멘토 세션 영속을 짧은 @Transactional로 분리(LLM 스트림은 MentorService에서 tx 밖). M-6: 완료 후 1행. */
@Service
public class MentorPersistenceService {

  private final AiMentorSessionRepository repo;

  public MentorPersistenceService(AiMentorSessionRepository repo) {
    this.repo = repo;
  }

  @Transactional
  public long saveDone(long userId, String question, Long contentId, String answer,
      String contextSnapshotJson, String referenceLinksJson, String provider) {
    AiMentorSession s = new AiMentorSession();
    s.setUserId(userId);
    s.setQuestion(question);
    s.setContentId(contentId);
    s.setAnswer(answer == null ? "" : answer);
    s.setContextSnapshot(contextSnapshotJson == null ? "{}" : contextSnapshotJson);
    s.setReferenceLinks(referenceLinksJson == null ? "[]" : referenceLinksJson);
    s.setProvider(provider);
    s.setStatus("DONE");
    return repo.save(s).getId();
  }

  @Transactional
  public long saveFailed(long userId, String question, Long contentId,
      String contextSnapshotJson, String errorCode) {
    AiMentorSession s = new AiMentorSession();
    s.setUserId(userId);
    s.setQuestion(question);
    s.setContentId(contentId);
    s.setContextSnapshot(contextSnapshotJson == null ? "{}" : contextSnapshotJson);
    s.setStatus("FAILED");
    s.setErrorCode(errorCode);
    return repo.save(s).getId();
  }
}
