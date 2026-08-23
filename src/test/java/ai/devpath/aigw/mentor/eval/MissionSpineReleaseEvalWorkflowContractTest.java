package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MissionSpineReleaseEvalWorkflowContractTest {

  private static final Path WORKFLOW =
      Path.of(".github/workflows/mission-spine-release-eval.yml");

  @SuppressWarnings("unchecked")
  @Test
  void workflowIsDispatchOnlyPinnedProtectedAndPublishesOneRunScopedFile()
      throws Exception {
    String text = Files.readString(WORKFLOW).replace("\r\n", "\n");
    Map<String, Object> workflow = new Yaml().load(text);
    Object triggerValue = workflow.containsKey("on")
        ? workflow.get("on")
        : workflow.get(Boolean.TRUE);
    Map<String, Object> triggers = (Map<String, Object>) triggerValue;
    Map<String, Object> dispatch = (Map<String, Object>) triggers.get("workflow_dispatch");
    Map<String, Object> inputs = (Map<String, Object>) dispatch.get("inputs");

    assertThat(triggers.keySet()).containsExactly("workflow_dispatch");
    assertThat(text).contains("\non:\n").doesNotContain("\n'on':\n", "\n\"on\":\n");
    assertThat(inputs.keySet()).containsExactly(
        "release_id", "candidate_spec_sha256", "ai_source_sha", "gitops_source_sha");
    for (Object raw : inputs.values()) {
      Map<String, Object> input = (Map<String, Object>) raw;
      assertThat(input.keySet()).containsExactly("description", "required", "type");
      assertThat(input).containsEntry("required", true).containsEntry("type", "string");
    }

    Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
    assertThat(jobs.keySet()).containsExactly("release-eval");
    Map<String, Object> job = (Map<String, Object>) jobs.get("release-eval");
    assertThat(job)
        .containsEntry("name", "Run AI release evaluation")
        .containsEntry("environment", "mission-spine-ai-release-eval")
        .containsEntry("runs-on", "ubuntu-24.04");

    assertThat(text)
        .contains("GITHUB_RUN_ATTEMPT", "refs/heads/main")
        .contains("kustomize_v5.4.3_linux_amd64.tar.gz")
        .contains("3669470b454d865c8184d6bce78df05e977c9aea31c30df3c669317d43bcc7a7")
        .contains("1d6bae90ee8591f7a4ed5b75be3f9bf80b7609f0c785921320827cd93e7c3a9a")
        .contains("15_101_952", "15101952")
        .contains("build apps/devpath-ai-svc/base")
        .contains("permission-members: read")
        .contains("persist-credentials: false")
        .contains("--candidate-spec-sha256=\"${CANDIDATE_SPEC_SHA256}\"")
        .contains("--candidate-output=\"${trust_root}/candidate-spec.json\"")
        .contains("test ! -e \"${trust_root}\"")
        .contains("install -d -m 0700 \"${trust_root}\"")
        .contains("scripts/release/validate_release_manifest.py")
        .contains("--expected-sha256 \"${CANDIDATE_SPEC_SHA256}\"")
        .contains("RENDERED_CONFIG: ${{ runner.temp }}/mission-spine-release-eval/rendered.yaml")
        .contains("Path(os.environ['RENDERED_CONFIG'])")
        .contains("./gradlew clean --no-build-cache")
        .contains("./gradlew test --tests 'ai.devpath.aigw.mentor.eval.*'")
        .contains("mentorReleaseArtifactFunctionalTest")
        .contains("ollama/ollama:0.32.5@sha256:98c19ced6600f2924e80b92d701cd867d8f7ef0c4dde516c619484e31e47f103")
        .contains("--publish 127.0.0.1:11434:11434")
        .contains("EXPECTED_OLLAMA_MODEL_DIGEST: 357c53fb659c5076de1d65ccb0b397446227b71a42be9d1603d46168015c9e4b")
        .contains("python3 tools/ollama_tls_proxy.py")
        .contains("--host 127.0.0.1", "--port 11435")
        .contains("keytool -importcert")
        .contains("-Djavax.net.ssl.trustStore=${{ runner.temp }}/mission-spine-release-eval/mentor-eval-cacerts")
        .contains("validateMissionSpineReleaseEvalCandidate")
        .contains("MISSION_SPINE_CANDIDATE: ${{ runner.temp }}/mission-spine-release-eval/candidate-spec.json")
        .contains("./gradlew generateMissionSpineReleaseEvalEvidence")
        .contains("./gradlew validateMissionSpineReleaseEvalEvidence")
        .contains("${{ inputs.release_id }}-ai-eval-run-${{ github.run_id }}-attempt-1")
        .contains("if-no-files-found: error")
        .doesNotContain(
            "continue-on-error",
            "always()",
            "pull_request:",
            "push:");

    List<String> actionLines = text.lines()
        .filter(line -> line.contains("uses:"))
        .toList();
    assertThat(actionLines).isNotEmpty();
    assertThat(actionLines)
        .allMatch(line -> line.matches(".*@[0-9a-f]{40}(?:\\s+#.*)?"));
    assertThat(text).contains(
        "actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803",
        "actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3",
        "actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1",
        "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02");
    assertThat(text.indexOf("--candidate-output=\"${trust_root}/candidate-spec.json\""))
        .isLessThan(text.indexOf("scripts/release/validate_release_manifest.py"));
    assertThat(text.indexOf("./gradlew clean --no-build-cache"))
        .isLessThan(text.indexOf("--candidate-output=\"${trust_root}/candidate-spec.json\""));
    assertThat(text.indexOf("./gradlew clean --no-build-cache"))
        .isEqualTo(text.lastIndexOf("./gradlew clean --no-build-cache"));
    assertThat(text.indexOf("--candidate-output=\"${trust_root}/candidate-spec.json\""))
        .isLessThan(text.indexOf("- name: Check out exact protected GitOps source"));
    assertThat(text.indexOf("- name: Check out exact protected GitOps source"))
        .isLessThan(text.indexOf("- name: Render exact canonical GitOps configuration"));
    assertThat(text.indexOf("- name: Render exact canonical GitOps configuration"))
        .isLessThan(text.indexOf("- name: Build and test exact ET9 release inputs without provider calls"));
    assertThat(text.indexOf("- name: Build and test exact ET9 release inputs without provider calls"))
        .isLessThan(text.indexOf("- name: Run live ET9 evaluation and create sanitized evidence"));
    assertThat(text.indexOf("scripts/release/validate_release_manifest.py"))
        .isLessThan(text.indexOf("validateMissionSpineReleaseEvalCandidate"));
    assertThat(text.indexOf("validateMissionSpineReleaseEvalCandidate"))
        .isLessThan(text.indexOf("GoldenMentorInjectionEvalTest"));
  }

  @Test
  void providerCredentialsAppearOnlyInsideTheProtectedLiveEvaluationStep()
      throws Exception {
    String text = Files.readString(WORKFLOW);
    assertThat(count(text, "ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}"))
        .isEqualTo(1);
    assertThat(count(text, "MENTOR_EVAL_OLLAMA_BASE_URL:"))
        .isEqualTo(1);
    assertThat(text)
        .contains("MENTOR_EVAL_OLLAMA_BASE_URL: https://127.0.0.1:11435")
        .doesNotContain("vars.MENTOR_EVAL_OLLAMA_BASE_URL");
    assertThat(text).contains("ollama_endpoint_sha256");
    assertThat(text).doesNotContain("synthetic", "mock-pass", "fixture-pass");
  }

  private static int count(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
  }
}
