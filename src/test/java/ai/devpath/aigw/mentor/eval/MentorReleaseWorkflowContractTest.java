package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MentorReleaseWorkflowContractTest {

  @SuppressWarnings("unchecked")
  @Test
  void imageAndDeployCannotRunWithoutValidatedTwoModelEvidence() throws Exception {
    Map<String, Object> workflow = new Yaml().load(
        Files.readString(Path.of(".github/workflows/ci.yml")));
    Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
    Map<String, Object> gate = (Map<String, Object>) jobs.get("release-model-eval");
    Map<String, Object> build = (Map<String, Object>) jobs.get("build");
    Map<String, Object> image = (Map<String, Object>) jobs.get("image");
    Map<String, Object> deploy = (Map<String, Object>) jobs.get("deploy");

    assertThat(gate).isNotNull();
    assertThat(gate.get("needs")).isEqualTo("build");
    assertThat((List<String>) image.get("needs"))
        .containsExactlyInAnyOrder("build", "release-model-eval");
    assertThat((List<String>) deploy.get("needs"))
        .contains("image", "release-model-eval");

    String gateText = gate.toString();
    String buildText = build.toString();
    String imageText = image.toString();
    String deployText = deploy.toString();
    assertThat(buildText).contains("prepareMentorReleaseArtifacts");
    assertThat(buildText).contains("mentor-release-inputs-${{ github.sha }}");
    assertThat(buildText).contains("actions/upload-artifact");
    assertThat(gateText).contains("actions/download-artifact");
    assertThat(gateText).contains("writeMentorCurrentRuntimeDependencyGraph");
    assertThat(gateText).contains("generateMentorReleaseEvalManifest");
    assertThat(gateText).contains("-Dgroups=eval");
    assertThat(gateText).contains("verifyMentorReleaseEvalEvidence");
    assertThat(gateText).contains("actions/upload-artifact");
    assertThat(gateText).contains("MENTOR_EVAL_SOURCE_REVISION=${{ github.sha }}");
    assertThat(gateText).contains("GITOPS_APP_PRIVATE_KEY");
    assertThat(gateText).doesNotContain("GITOPS_PRIVATE_KEY");
    assertThat(gateText).doesNotContain("MENTOR_EVAL_CLAUDE_BASE_URL");
    assertThat(gateText).doesNotContain("continue-on-error=true");
    assertThat(gateText).doesNotContain("always()");
    assertThat(gateText).contains("MENTOR_EVAL_BOOT_JAR");
    assertThat(gateText).contains("MENTOR_EVAL_SHARED_ARTIFACT");
    assertThat(gateText).contains("MENTOR_EVAL_DEPENDENCY_GRAPH");
    assertThat(gateText).contains("MENTOR_EVAL_CURRENT_DEPENDENCY_GRAPH");
    assertThat(gateText).contains("mentor-release-eval-manifest-v2.json");
    assertThat(imageText).contains("actions/download-artifact");
    assertThat(imageText).contains("mentor-release-inputs-${{ github.sha }}");
    assertThat(imageText).doesNotContain("./gradlew bootJar", "--refresh-dependencies");
    assertThat(deployText)
        .contains("needs.release-model-eval.outputs.gitops-revision");
    assertThat(deployText).contains("bash ../source/.github/scripts/push-evaluated-gitops.sh");
    assertThat(count(deployText, "bash ../source/.github/scripts/push-evaluated-gitops.sh"))
        .isEqualTo(2);
    assertThat(deployText).doesNotContain("pull --rebase", "for i in");
  }

  private static int count(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
  }
}
