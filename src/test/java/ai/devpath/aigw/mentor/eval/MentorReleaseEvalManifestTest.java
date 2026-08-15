package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.aigw.mentor.MentorPromptBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MentorReleaseEvalManifestTest {

  private static final String SOURCE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String GITOPS = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  @TempDir Path temp;

  @Test
  void generatedManifestBindsSourceGitOpsRenderedModelsPromptAndFixture() throws Exception {
    MentorReleaseEvalManifest.Inputs inputs = inputs(SOURCE, GITOPS, rendered());

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(inputs);
    Path path = temp.resolve("mentor-release-eval-manifest-v1.json");
    manifest.write(path);
    MentorReleaseEvalManifest loaded = MentorReleaseEvalManifest.read(path);
    loaded.validate(inputs);

    assertThat(loaded.schemaVersion()).isEqualTo("mentor-release-eval/v1");
    assertThat(loaded.models()).extracting(MentorReleaseEvalManifest.Model::role)
        .containsExactly("primary", "fallback");
    assertThat(loaded.models()).extracting(MentorReleaseEvalManifest.Model::provider)
        .containsExactly("ollama", "claude");
    assertThat(loaded.models()).extracting(MentorReleaseEvalManifest.Model::modelId)
        .containsExactly("qwen2.5:3b", "claude-sonnet-4-6");
    assertThat(loaded.fixtureSha256()).hasSize(64);
    assertThat(loaded.promptSha256()).hasSize(64);
    assertThat(loaded.hardInvariantMinimum()).isEqualTo(1.0);
    assertThat(loaded.qualityMinimum()).isEqualTo(0.90);
  }

  @Test
  void rejectsWrongSourceGitOpsConfigFixtureAndPromptHashes() throws Exception {
    Path render = rendered();
    MentorReleaseEvalManifest.Inputs valid = inputs(SOURCE, GITOPS, render);
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(valid);

    assertThatThrownBy(() -> manifest.validate(inputs("c".repeat(40), GITOPS, render)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manifest.validate(inputs(SOURCE, "d".repeat(40), render)))
        .isInstanceOf(IllegalArgumentException.class);

    Files.writeString(render, Files.readString(render).replace("qwen2.5:3b", "qwen2.5:7b"));
    assertThatThrownBy(() -> manifest.validate(inputs(SOURCE, GITOPS, render)))
        .isInstanceOf(IllegalArgumentException.class);

    MentorReleaseEvalManifest wrongFixture = manifest.withFixtureSha256("e".repeat(64));
    assertThatThrownBy(() -> wrongFixture.validate(valid))
        .isInstanceOf(IllegalArgumentException.class);
    MentorReleaseEvalManifest wrongPrompt = manifest.withPromptSha256("f".repeat(64));
    assertThatThrownBy(() -> wrongPrompt.validate(valid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsManifestWhenDeclaredSourceDoesNotMatchCheckedOutRevision() throws Exception {
    MentorReleaseEvalManifest.Inputs mismatched = inputs(SOURCE, GITOPS, rendered())
        .withCheckedOutSourceRevision("c".repeat(40));

    assertThatThrownBy(() -> MentorReleaseEvalManifest.create(mismatched))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checked-out source");
  }

  @Test
  void rejectsProviderEndpointsThatCouldPersistCredentials() throws Exception {
    MentorReleaseEvalManifest.Inputs withQuerySecret = inputs(SOURCE, GITOPS, rendered())
        .withEvaluationEndpoints(java.util.Map.of(
            "ollama", "https://eval.example.test/api?token=synthetic-secret"));

    assertThatThrownBy(() -> MentorReleaseEvalManifest.create(withQuerySecret))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint");
  }

  @Test
  void rejectsFallbackWhoseCredentialIdentityIsAbsentFromRuntimeConfig() throws Exception {
    Path rendered = rendered();
    Files.writeString(rendered, Files.readString(rendered).replace("""
                    - name: ANTHROPIC_API_KEY
                      valueFrom:
                        secretKeyRef:
                          name: ai-provider-credentials
                          key: anthropic-api-key
        """, ""));

    assertThatThrownBy(() -> MentorReleaseEvalManifest.create(inputs(SOURCE, GITOPS, rendered)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("credential identity");
  }

  @Test
  void rejectsSwappedModelsAndMissingFallback() throws Exception {
    MentorReleaseEvalManifest.Inputs inputs = inputs(SOURCE, GITOPS, rendered());
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(inputs);

    assertThatThrownBy(() -> manifest.withModels(List.of(
        manifest.models().get(1), manifest.models().get(0))).validate(inputs))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manifest.withModels(List.of(manifest.models().get(0))).validate(inputs))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsStaleOrMismatchedEvidence() throws Exception {
    MentorReleaseEvalManifest.Inputs inputs = inputs(SOURCE, GITOPS, rendered());
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(inputs);
    Path manifestPath = temp.resolve("manifest.json");
    manifest.write(manifestPath);
    Instant now = Instant.parse("2026-08-16T12:00:00Z");
    MentorEvalEvidence evidence = MentorEvalEvidence.passing(
        manifest, MentorReleaseEvalManifest.sha256(manifestPath), now.minusSeconds(7200),
        List.of(
            new MentorEvalEvidence.ModelResult("primary", "ollama", "qwen2.5:3b", 1.0, 0.95, 10),
            new MentorEvalEvidence.ModelResult("fallback", "claude", "claude-sonnet-4-6", 1.0, 0.95, 11)));

    assertThatThrownBy(() -> evidence.validate(
        manifest, MentorReleaseEvalManifest.sha256(manifestPath),
        Clock.fixed(now, ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> evidence.withManifestSha256("0".repeat(64)).validate(
        manifest, MentorReleaseEvalManifest.sha256(manifestPath),
        Clock.fixed(now.minusSeconds(7100), ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonFiniteOrOutOfRangeEvidenceScores() throws Exception {
    MentorReleaseEvalManifest.Inputs inputs = inputs(SOURCE, GITOPS, rendered());
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(inputs);
    Path manifestPath = temp.resolve("manifest.json");
    manifest.write(manifestPath);
    Instant now = Instant.parse("2026-08-16T12:00:00Z");
    MentorEvalEvidence malformed = new MentorEvalEvidence(
        "mentor-release-eval-evidence/v1",
        MentorReleaseEvalManifest.sha256(manifestPath), manifest.releaseId(),
        manifest.sourceRevision(), manifest.gitOpsRevision(), manifest.renderedConfigSha256(),
        manifest.promptSha256(), manifest.fixtureRevision(), manifest.fixtureSha256(),
        now.toString(), "PASS", Double.NaN, 0.95, manifest.requiredQualityRate(),
        List.of(
            new MentorEvalEvidence.ModelResult(
                "primary", "ollama", "qwen2.5:3b", Double.NaN, 0.95, 10),
            new MentorEvalEvidence.ModelResult(
                "fallback", "claude", "claude-sonnet-4-6", 1.0, 0.95, 11)));

    assertThatThrownBy(() -> malformed.validate(
        manifest, MentorReleaseEvalManifest.sha256(manifestPath),
        Clock.fixed(now, ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private MentorReleaseEvalManifest.Inputs inputs(
      String source, String gitOps, Path rendered) {
    Path fixture = Path.of("src/test/resources/eval/golden-mentor-injection.jsonl");
    return new MentorReleaseEvalManifest.Inputs(
        "devpath-ai-" + source, source, source, gitOps, rendered,
        Path.of("src/main/resources/application.yml"), fixture,
        MentorGoldenCase.load(fixture), new MentorPromptBuilder(),
        java.util.Map.of("ollama", "https://eval-ollama.example.test"));
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
