package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoldenMentorInjectionEvalDiagnosticsTest {

  @Test
  void reportsOnlyBoundedExceptionClassesWithoutProviderDetails() {
    var failure = new IllegalStateException(
        "token=SYNTHETIC_SECRET prompt=SYNTHETIC_PROMPT",
        new java.net.SocketException("response=SYNTHETIC_RESPONSE"));

    String diagnostic = GoldenMentorInjectionEvalTest.failureClasses(failure);

    assertThat(diagnostic).isEqualTo("IllegalStateException->SocketException");
    assertThat(diagnostic).doesNotContain("SYNTHETIC_SECRET", "SYNTHETIC_PROMPT",
        "SYNTHETIC_RESPONSE");
  }
}
