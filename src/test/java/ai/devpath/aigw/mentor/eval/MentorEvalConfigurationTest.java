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
  void legacyIndependentModelPropertiesCannotOverrideRenderedRuntimeModels() throws Exception {
    Path rendered = rendered();
    Map<String, String> environment = environment(rendered);
    environment.put("mentor.eval.primary-model", "claude/wrong-model");
    environment.put("mentor.eval.fallback-model", "ollama/wrong-model");

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment));

    assertThat(manifest.models()).extracting(MentorReleaseEvalManifest.Model::provider)
        .containsExactly("ollama", "claude");
    assertThat(manifest.models()).extracting(MentorReleaseEvalManifest.Model::modelId)
        .containsExactly("qwen2.5:3b", "claude-sonnet-4-6");
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
  void missingFallbackCredentialFailsPreflightBeforeAnyModelCall() throws Exception {
    Map<String, String> environment = environment(rendered());
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment));

    assertThatThrownBy(() -> GoldenMentorInjectionEvalTest.validateCredentials(
        manifest, environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ANTHROPIC_API_KEY")
        .hasMessageNotContaining("synthetic-secret");
  }

  @Test
  void credentialedClaudeEndpointCannotBeRedirectedByAnEvalOverride() throws Exception {
    Map<String, String> environment = environment(rendered());
    environment.put("MENTOR_EVAL_CLAUDE_BASE_URL", "https://attacker.example.test");

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment));

    assertThat(manifest.models().get(1).evaluationEndpoint())
        .isEqualTo("https://api.anthropic.com");
  }

  private Map<String, String> environment(Path rendered) {
    Map<String, String> environment = new HashMap<>();
    String source = MentorReleaseEvalManifest.Inputs.resolveCheckedOutSourceRevision();
    environment.put("MENTOR_EVAL_RELEASE_ID", "devpath-ai-" + source);
    environment.put("MENTOR_EVAL_SOURCE_REVISION", source);
    environment.put("MENTOR_EVAL_GITOPS_REVISION", "b".repeat(40));
    environment.put("MENTOR_EVAL_RENDERED_CONFIG", rendered.toString());
    environment.put("MENTOR_EVAL_OLLAMA_BASE_URL", "https://eval-ollama.example.test");
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
