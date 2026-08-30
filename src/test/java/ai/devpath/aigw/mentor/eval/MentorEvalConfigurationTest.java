package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MentorEvalConfigurationTest {

  @TempDir Path temp;

  @Test
  void developmentEvaluationUsesOneVersionedOllamaTuningInsteadOfRuntimeProviders()
      throws Exception {
    Path rendered = rendered();
    Map<String, String> environment = environment(rendered);
    environment.put("mentor.eval.primary-model", "claude/wrong-model");
    environment.put("mentor.eval.fallback-model", "ollama/wrong-model");

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment));

    assertThat(manifest.runtimePrimaryModel()).isEqualTo("qwen2.5:3b");
    assertThat(manifest.runtimeFallbackModels()).containsExactly("claude-sonnet-4-6");
    assertThat(manifest.models()).extracting(MentorReleaseEvalManifest.Model::provider)
        .containsExactly("ollama");
    assertThat(manifest.models()).extracting(MentorReleaseEvalManifest.Model::role)
        .containsExactly("development-eval");
    assertThat(manifest.models()).extracting(MentorReleaseEvalManifest.Model::modelId)
        .containsExactly("devpath-mentor-eval:mentor-development-tuning-v1");
    assertThat(manifest.tuningRevision()).isEqualTo("mentor-development-tuning-v1");
    assertThat(manifest.tuningSha256()).matches("[0-9a-f]{64}");
  }

  @Test
  void missingEvalEndpointFailsClosedBeforeAnyModelCall() throws Exception {
    Map<String, String> environment = environment(rendered());
    environment.remove("MENTOR_EVAL_OLLAMA_BASE_URL");

    assertThatThrownBy(() -> MentorReleaseEvalManifest.Inputs.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MENTOR_EVAL_OLLAMA_BASE_URL");
  }

  @Test
  void insecureOrAmbiguousOllamaEvalEndpointFailsBeforeAnyModelCall() throws Exception {
    for (String invalid : java.util.List.of(
        "http://eval-ollama.example.test/api",
        "https://user@eval-ollama.example.test/api",
        "https://eval-ollama.example.test/api?mode=mock",
        "https://eval-ollama.example.test/api#mock",
        "https://eval-ollama.example.test/api/../mock",
        "https://eval-ollama.example.test/api//mock")) {
      Map<String, String> environment = environment(rendered());
      environment.put("MENTOR_EVAL_OLLAMA_BASE_URL", invalid);

      assertThatThrownBy(() -> MentorReleaseEvalManifest.create(
          MentorReleaseEvalManifest.Inputs.fromEnvironment(environment)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Ollama evaluation endpoint");
    }
  }

  @Test
  void developmentEvaluationRequiresNoRemoteProviderCredential() throws Exception {
    Map<String, String> environment = environment(rendered());
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment));

    GoldenMentorInjectionEvalTest.validateNoRemoteCredentials(manifest);
    assertThat(manifest.models())
        .extracting(MentorReleaseEvalManifest.Model::credentialEnv)
        .containsOnlyNulls();
  }

  @Test
  void remoteClaudeEvalOverrideCannotAddAProviderCall() throws Exception {
    Map<String, String> environment = environment(rendered());
    environment.put("MENTOR_EVAL_CLAUDE_BASE_URL", "https://attacker.example.test");

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment));

    assertThat(manifest.models()).hasSize(1);
    assertThat(manifest.models().getFirst().provider()).isEqualTo("ollama");
  }

  @Test
  void missingTuningRecipeFailsClosedBeforeAnyModelCall() throws Exception {
    Map<String, String> environment = environment(rendered());
    environment.put("MENTOR_EVAL_TUNING_RECIPE", temp.resolve("missing.Modelfile").toString());

    assertThatThrownBy(() -> MentorReleaseEvalManifest.create(
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tuning recipe");
  }

  private Map<String, String> environment(Path rendered) {
    Map<String, String> environment = new HashMap<>();
    String source = MentorReleaseEvalManifest.Inputs.resolveCheckedOutSourceRevision();
    environment.put("MENTOR_EVAL_RELEASE_ID", "devpath-ai-" + source);
    environment.put("MENTOR_EVAL_SOURCE_REVISION", source);
    environment.put("MENTOR_EVAL_GITOPS_REVISION", "b".repeat(40));
    environment.put("MENTOR_EVAL_RENDERED_CONFIG", rendered.toString());
    environment.put("MENTOR_EVAL_OLLAMA_BASE_URL", "https://eval-ollama.example.test");
    environment.put(
        "MENTOR_EVAL_MODEL", "devpath-mentor-eval:mentor-development-tuning-v1");
    environment.put("MENTOR_EVAL_TUNING_RECIPE",
        Path.of("src/test/resources/eval/mentor-development-tuning-v1.Modelfile")
            .toString());
    MentorReleaseArtifactFixture.addToEnvironment(environment, temp.resolve("release-artifacts"));
    return environment;
  }

  private Path rendered() throws Exception {
    Path path = temp.resolve("rendered.yaml");
    Files.writeString(path, """
        apiVersion: apps/v1
        kind: Deployment
        spec:
          template:
            spec:
              containers:
                - name: devpath-ai-svc
                  env:
                    - name: MENTOR_PROVIDER
                      value: "ollama"
                    - name: MENTOR_FALLBACK
                      value: "claude"
                    - name: MENTOR_OLLAMA_MODEL
                      value: "qwen2.5:3b"
                    - name: OLLAMA_BASE_URL
                      value: "http://ollama.devpath.svc:11434"
                    - name: ANTHROPIC_API_KEY
                      valueFrom:
                        secretKeyRef:
                          name: ai-provider-credentials
                          key: anthropic-api-key
        """);
    return path;
  }
}
