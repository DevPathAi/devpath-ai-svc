package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ai.devpath.aigw.review.SandboxClient;
import ai.devpath.aigw.review.SandboxSessionView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MentorContextAssemblerTest {

  @Mock SandboxClient sandboxClient;
  @Mock LearningClient learningClient;

  private MentorContextAssembler assembler() {
    return new MentorContextAssembler(sandboxClient, learningClient, JsonMapper.builder().build());
  }

  @Test
  void assemblesContentAndSandboxIntoContext() {
    when(learningClient.getContent(7L)).thenReturn(Optional.of(
        new InternalContentView(7, "async", "비동기 기초", "BACKEND_SPRING", "콘텐츠 본문")));
    when(sandboxClient.recentByUser(42L, 5)).thenReturn(List.of(
        new SandboxSessionView(2L, 42L, "PYTHON", 7L, "print(2)", "2", "", 0, "COMPLETED")));

    MentorContext ctx = assembler().assemble(42L, 7L);

    assertThat(ctx.track()).isEqualTo("BACKEND_SPRING");
    assertThat(ctx.promptText()).contains("비동기 기초").contains("print(2)");
    assertThat(ctx.snapshotJson()).contains("BACKEND_SPRING");
  }

  @Test
  void worksWithoutContentId() {
    when(sandboxClient.recentByUser(42L, 5)).thenReturn(List.of());

    MentorContext ctx = assembler().assemble(42L, null);

    assertThat(ctx.track()).isNull();
    assertThat(ctx.promptText()).isNotNull();
    // contentId 없으면 learningClient.getContent 미호출
  }
}
