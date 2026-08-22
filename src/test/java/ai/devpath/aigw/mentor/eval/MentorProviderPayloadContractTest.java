package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.aigw.mentor.MentorPromptBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class MentorProviderPayloadContractTest {

  @Test
  void sensitiveFieldsArePresentInOnPayloadAndAbsentFromTheNextOffPayload() {
    List<MentorGoldenCase> cases =
        MentorGoldenCase.load("/eval/golden-mentor-injection.jsonl");

    List<MentorProviderPayloadContract.Result> results =
        MentorProviderPayloadContract.validate(cases, new MentorPromptBuilder());

    assertThat(results).extracting(MentorProviderPayloadContract.Result::sequenceId)
        .containsExactly("current-code-one-request", "recent-errors-off", "recent-output-off");
    assertThat(results).allSatisfy(result -> {
      assertThat(result.onPayloadContainsSentinel()).isTrue();
      assertThat(result.offPayloadContainsSentinel()).isFalse();
      assertThat(result.onPayloadSha256()).hasSize(64);
      assertThat(result.offPayloadSha256()).hasSize(64);
    });
  }
}
