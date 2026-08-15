package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MentorEvalConfigurationTest {

  @Test
  void parsesReleaseSuppliedProviderAndFullModelIdWithoutDevelopmentDefault() {
    GoldenMentorInjectionEvalTest.ModelSpec spec =
        GoldenMentorInjectionEvalTest.ModelSpec.parse("fallback", "ollama/qwen2.5:14b");

    assertThat(spec.role()).isEqualTo("fallback");
    assertThat(spec.provider()).isEqualTo("ollama");
    assertThat(spec.model()).isEqualTo("qwen2.5:14b");
  }

  @Test
  void rejectsMissingOrUnsupportedReleaseModelSpecs() {
    assertThatThrownBy(() -> GoldenMentorInjectionEvalTest.ModelSpec.parse("primary", "qwen2.5:7b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GoldenMentorInjectionEvalTest.ModelSpec.parse("fallback", "other/model"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
