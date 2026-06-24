package ai.devpath.aigw.mentor;

import ai.devpath.aigw.review.SandboxClient;
import ai.devpath.aigw.review.SandboxSessionView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** context_snapshot 조립(M-3): 현재 콘텐츠(옵셔널) + 최근 5 Sandbox. 결손 허용(클라이언트가 빈값 폴백). */
@Component
public class MentorContextAssembler {

  private static final int RECENT_LIMIT = 5;
  private static final int MAX_CODE_CHARS = 1500;
  private static final int MAX_BODY_CHARS = 2000;

  private final SandboxClient sandboxClient;
  private final LearningClient learningClient;
  private final JsonMapper jsonMapper;

  public MentorContextAssembler(SandboxClient sandboxClient, LearningClient learningClient,
      JsonMapper jsonMapper) {
    this.sandboxClient = sandboxClient;
    this.learningClient = learningClient;
    this.jsonMapper = jsonMapper;
  }

  public MentorContext assemble(long userId, Long contentId) {
    Optional<InternalContentView> content = contentId == null
        ? Optional.empty()
        : learningClient.getContent(contentId);
    List<SandboxSessionView> recent = sandboxClient.recentByUser(userId, RECENT_LIMIT);

    StringBuilder prompt = new StringBuilder();
    Map<String, Object> snapshot = new LinkedHashMap<>();

    content.ifPresent(c -> {
      prompt.append("현재 학습 콘텐츠: ").append(c.title())
          .append(" (track: ").append(c.track()).append(")\n")
          .append(truncate(c.body(), MAX_BODY_CHARS)).append("\n\n");
      snapshot.put("track", c.track());
      snapshot.put("contentTitle", c.title());
      snapshot.put("contentId", c.id());
    });

    if (!recent.isEmpty()) {
      prompt.append("최근 코드 실행:\n");
      for (SandboxSessionView s : recent) {
        prompt.append("- [").append(s.language()).append(", ").append(s.status()).append("] ")
            .append(truncate(s.submittedCode(), MAX_CODE_CHARS))
            .append(" => exit ").append(s.exitCode()).append("\n");
      }
      snapshot.put("recentRuns", recent.size());
    }

    return new MentorContext(
        prompt.toString(),
        toJson(snapshot),
        content.map(InternalContentView::track).orElse(null));
  }

  private String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  private String toJson(Map<String, Object> snapshot) {
    try {
      return jsonMapper.writeValueAsString(snapshot);
    } catch (Exception e) {
      return "{}";
    }
  }
}
