package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MentorPersistenceServiceTest {

  @Autowired MentorPersistenceService persistence;
  @Autowired AiMentorSessionRepository repo;

  @Test
  void saveDonePersistsAnswerContextAndReferences() {
    long id = persistence.saveDone(42L, "비동기란?", 7L, "비동기는 Future로 다룹니다.",
        "{\"track\":\"BACKEND_SPRING\"}", "[{\"contentId\":1,\"slug\":\"a\",\"title\":\"t\"}]", "MOCK");

    AiMentorSession s = repo.findById(id).orElseThrow();
    assertThat(s.getStatus()).isEqualTo("DONE");
    assertThat(s.getUserId()).isEqualTo(42L);
    assertThat(s.getContentId()).isEqualTo(7L);
    assertThat(s.getQuestion()).isEqualTo("비동기란?");
    assertThat(s.getAnswer()).isEqualTo("비동기는 Future로 다룹니다.");
    assertThat(s.getProvider()).isEqualTo("MOCK");
    assertThat(s.getContextSnapshot()).contains("BACKEND_SPRING");
    assertThat(s.getReferenceLinks()).contains("slug");
    repo.deleteById(id);
  }

  @Test
  void saveFailedPersistsErrorCodeAndEmptyAnswer() {
    long id = persistence.saveFailed(42L, "q", null, "{}", "LLM_FAILED");

    AiMentorSession s = repo.findById(id).orElseThrow();
    assertThat(s.getStatus()).isEqualTo("FAILED");
    assertThat(s.getErrorCode()).isEqualTo("LLM_FAILED");
    assertThat(s.getAnswer()).isEmpty();
    repo.deleteById(id);
  }
}
