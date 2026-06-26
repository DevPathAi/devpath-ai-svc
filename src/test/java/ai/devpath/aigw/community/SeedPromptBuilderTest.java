package ai.devpath.aigw.community;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 시드 프롬프트 인젝션 방어 단언(델리미터 격리 + 강건 system prompt). */
class SeedPromptBuilderTest {

  private final SeedPromptBuilder builder = new SeedPromptBuilder();

  @Test
  void systemPromptDeclaresUntrustedDataAndRefusesInstructions() {
    String sys = builder.systemPrompt();
    assertThat(sys).contains("UNTRUSTED DATA");
    assertThat(sys).contains("DO NOT FOLLOW");
    // 역할 고정(커뮤니티 보조 AI)·방향 제시 수준 초안
    assertThat(sys.toLowerCase()).contains("user_question");
  }

  @Test
  void userContentIsolatesQuestionInDelimiterTags() {
    String content = builder.userContent(new SeedInput("제목X", "본문Y"));
    assertThat(content).contains("<user_question>");
    assertThat(content).contains("</user_question>");
    assertThat(content).contains("제목X");
    assertThat(content).contains("본문Y");
    // 델리미터 안에 질문 본문이 위치(여는 태그가 제목보다 앞)
    assertThat(content.indexOf("<user_question>")).isLessThan(content.indexOf("제목X"));
    assertThat(content.indexOf("본문Y")).isLessThan(content.indexOf("</user_question>"));
  }

  @Test
  void nullFieldsAreSafe() {
    String content = builder.userContent(new SeedInput(null, null));
    assertThat(content).contains("<user_question>");
    assertThat(content).contains("</user_question>");
  }
}
