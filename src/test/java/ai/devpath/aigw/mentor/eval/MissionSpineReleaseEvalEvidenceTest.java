package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devpath.aigw.mentor.MentorPromptBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MissionSpineReleaseEvalEvidenceTest {

  private static final String SOURCE = "a".repeat(40);
  private static final String GITOPS = "b".repeat(40);
  private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path temp;

  @Test
  void writesAndRevalidatesTheExactSanitizedGitOpsPayload() throws Exception {
    Fixture fixture = fixture();
    MissionSpineReleaseEvalEvidence evidence =
        MissionSpineReleaseEvalEvidence.create(fixture.context());
    Path output = temp.resolve("evidence.json");

    evidence.write(output);
    MissionSpineReleaseEvalEvidence.validate(output, fixture.context());

    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(Files.readAllBytes(output), Map.class);
    assertThat(payload.keySet()).containsExactly(
        "candidate_spec_sha256",
        "status",
        "producer_run_id",
        "producer_run_attempt",
        "ai_source_sha",
        "gitops_source_sha",
        "primary_model",
        "fallback_models",
        "prompt_sha256",
        "fixture_revision",
        "fixture_sha256",
        "rendered_config_sha256",
        "ollama_endpoint_sha256",
        "hard_invariants_percent",
        "usefulness_percent",
        "baseline_delta_points",
        "approval_environment",
        "approval_environment_id",
        "approval_job_name",
        "approved_by",
        "approved_by_id",
        "approval_effective_at");
    assertThat(payload)
        .containsEntry("candidate_spec_sha256", fixture.candidateSha())
        .containsEntry("status", "passed")
        .containsEntry("producer_run_id", 501)
        .containsEntry("producer_run_attempt", 1)
        .containsEntry("ai_source_sha", SOURCE)
        .containsEntry("gitops_source_sha", GITOPS)
        .containsEntry("primary_model", "qwen2.5:3b")
        .containsEntry("fixture_revision", "mentor-golden-v2")
        .containsEntry("hard_invariants_percent", 100.0)
        .containsEntry("usefulness_percent", 95.0)
        .containsEntry("baseline_delta_points", 3.0)
        .containsEntry("approval_environment", "mission-spine-ai-release-eval")
        .containsEntry("approval_job_name", "Run AI release evaluation")
        .containsEntry("approved_by", "independent-reviewer");
    assertThat(payload.get("fallback_models"))
        .isEqualTo(List.of("claude-sonnet-4-6"));
    assertThat(payload.get("rendered_config_sha256"))
        .isEqualTo(MentorReleaseEvalManifest.sha256(fixture.inputs().renderedConfig()));
    assertThat(payload.get("ollama_endpoint_sha256"))
        .isEqualTo(MentorReleaseEvalManifest.sha256("https://eval-ollama.example.test/api"));
    assertThat(Files.readString(output)).doesNotContain(
        "question", "answer", "prompt_text", "token", "latency",
        "https://", "eval-ollama");
  }

  @Test
  void rejectsFieldExtrasIdentityDriftThresholdDriftAndAttemptReuse() throws Exception {
    Fixture fixture = fixture();
    Path output = temp.resolve("evidence.json");
    MissionSpineReleaseEvalEvidence.create(fixture.context()).write(output);
    byte[] valid = Files.readAllBytes(output);

    List<Consumer<Map<String, Object>>> mutations = List.of(
        payload -> payload.put("candidate_spec_sha256", "d".repeat(64)),
        payload -> payload.put("status", "failed"),
        payload -> payload.put("producer_run_attempt", 2),
        payload -> payload.put("ai_source_sha", "e".repeat(40)),
        payload -> payload.put("gitops_source_sha", "f".repeat(40)),
        payload -> payload.put("primary_model", "forged-model"),
        payload -> payload.put("fallback_models", List.of("forged-fallback")),
        payload -> payload.put("prompt_sha256", "1".repeat(64)),
        payload -> payload.put("fixture_revision", "forged-fixture"),
        payload -> payload.put("fixture_sha256", "2".repeat(64)),
        payload -> payload.put("rendered_config_sha256", "3".repeat(64)),
        payload -> payload.put("ollama_endpoint_sha256", "4".repeat(64)),
        payload -> payload.put("hard_invariants_percent", 99.9),
        payload -> payload.put("usefulness_percent", 89.9),
        payload -> payload.put("baseline_delta_points", -5.1),
        payload -> payload.put("approval_environment", "unprotected"),
        payload -> payload.put("approved_by_id", 0),
        payload -> payload.put("approval_effective_at", "2026-08-17T12:00:00+09:00"),
        payload -> payload.put("prompt", "forbidden raw prompt"));

    for (Consumer<Map<String, Object>> mutation : mutations) {
      @SuppressWarnings("unchecked")
      Map<String, Object> changed = new LinkedHashMap<>(
          MAPPER.readValue(valid, Map.class));
      mutation.accept(changed);
      Files.writeString(output, MAPPER.writeValueAsString(changed) + "\n");
      assertThatThrownBy(() ->
          MissionSpineReleaseEvalEvidence.validate(output, fixture.context()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesToAdaptFailedOrSyntheticThresholdEvidence() throws Exception {
    Fixture fixture = fixture();
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.read(fixture.manifest());
    MentorEvalEvidence failed = MentorEvalEvidence.evaluated(
        manifest,
        MentorReleaseEvalManifest.sha256(fixture.manifest()),
        NOW.minusSeconds(10),
        List.of(
            new MentorEvalEvidence.ModelResult(
                "primary", "ollama", "qwen2.5:3b", 1.0, 0.89, 10),
            new MentorEvalEvidence.ModelResult(
                "fallback", "claude", "claude-sonnet-4-6", 1.0, 0.95, 11)));
    failed.write(fixture.evaluation());

    assertThatThrownBy(() -> MissionSpineReleaseEvalEvidence.create(fixture.context()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonCanonicalRenderedConfigBytes() throws Exception {
    Fixture fixture = fixture();
    String rendered = Files.readString(fixture.inputs().renderedConfig());
    Files.writeString(
        fixture.inputs().renderedConfig(), rendered.replace("\n", "\r\n"));

    assertThatThrownBy(() -> MentorReleaseEvalManifest.create(fixture.inputs()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical UTF-8 LF");
  }

  @Test
  void canonicalizesAndBindsOnlyAnAbsoluteHttpsOllamaEvaluationEndpoint() throws Exception {
    assertThat(MissionSpineReleaseEvalEvidence.ollamaEndpointSha256(
        "https://EVAL-OLLAMA.example.test:443/api/"))
        .isEqualTo(MentorReleaseEvalManifest.sha256(
            "https://eval-ollama.example.test/api"));

    for (String invalid : List.of(
        "http://eval-ollama.example.test/api",
        "https://user@eval-ollama.example.test/api",
        "https://eval-ollama.example.test/api?mode=mock",
        "https://eval-ollama.example.test/api#mock",
        "https://eval-ollama.example.test/api/../mock",
        "https://eval-ollama.example.test/api//mock")) {
      assertThatThrownBy(() ->
          MissionSpineReleaseEvalEvidence.ollamaEndpointSha256(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void candidateBytesBindExactSourceModelsPromptFixtureRenderAndEndpoint() throws Exception {
    Fixture fixture = fixture();
    @SuppressWarnings("unchecked")
    Map<String, Object> valid = MAPPER.readValue(
        Files.readAllBytes(fixture.candidate()), Map.class);
    List<Consumer<Map<String, Object>>> mutations = List.of(
        candidate -> candidate.put("document_type", "release"),
        candidate -> candidate.put("release_id", "ms-20260817-other"),
        candidate -> object(candidate, "gitops").put("base_sha", "d".repeat(40)),
        candidate -> object(candidate, "gitops").put("repository", "attacker/gitops"),
        candidate -> object(object(candidate, "services"), "devpath-ai-svc")
            .put("source_sha", "e".repeat(40)),
        candidate -> object(object(candidate, "services"), "devpath-ai-svc")
            .put("repository", "attacker/ai"),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("primary_model", "forged-primary"),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("fallback_models", List.of("forged-fallback")),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("prompt_sha256", "1".repeat(64)),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("fixture_revision", "forged-fixture"),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("fixture_sha256", "2".repeat(64)),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("rendered_config_sha256", "3".repeat(64)),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("ollama_endpoint_sha256", "4".repeat(64)),
        candidate -> object(candidate, "ai_release_eval_config")
            .put("unexpected", "forbidden"));

    for (Consumer<Map<String, Object>> mutation : mutations) {
      @SuppressWarnings("unchecked")
      Map<String, Object> changed = MAPPER.readValue(
          MAPPER.writeValueAsBytes(valid), Map.class);
      mutation.accept(changed);
      Files.writeString(
          fixture.candidate(), MAPPER.writeValueAsString(changed) + "\n");
      MissionSpineReleaseEvalEvidence.Context context =
          candidateContext(fixture, MentorReleaseEvalManifest.sha256(fixture.candidate()));
      assertThatThrownBy(() -> MissionSpineReleaseEvalEvidence.create(context))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void candidateParserRejectsDuplicateTrailingAndUnboundRawBytes() throws Exception {
    Fixture fixture = fixture();
    String valid = Files.readString(fixture.candidate());

    String duplicate = valid.replaceFirst(
        "\\{", "{\\\"document_type\\\":\\\"candidate-spec\\\",");
    Files.writeString(fixture.candidate(), duplicate);
    assertThatThrownBy(() -> MissionSpineReleaseEvalEvidence.create(
        candidateContext(fixture, MentorReleaseEvalManifest.sha256(fixture.candidate()))))
        .isInstanceOf(IllegalArgumentException.class);

    Files.writeString(fixture.candidate(), valid + "{}\n");
    assertThatThrownBy(() -> MissionSpineReleaseEvalEvidence.create(
        candidateContext(fixture, MentorReleaseEvalManifest.sha256(fixture.candidate()))))
        .isInstanceOf(IllegalArgumentException.class);

    Files.writeString(fixture.candidate(), valid);
    assertThatThrownBy(() -> MissionSpineReleaseEvalEvidence.create(
        candidateContext(fixture, "9".repeat(64))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Fixture fixture() throws Exception {
    Path rendered = temp.resolve("rendered.yaml");
    Files.writeString(rendered, """
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
    Path golden = Path.of("src/test/resources/eval/golden-mentor-injection.jsonl");
    MentorReleaseArtifactFixture.Artifacts artifacts =
        MentorReleaseArtifactFixture.create(temp.resolve("release-artifacts"));
    MentorReleaseEvalManifest.Inputs inputs = new MentorReleaseEvalManifest.Inputs(
        "ms-20260817-et9", SOURCE, SOURCE, GITOPS, rendered,
        Path.of("src/main/resources/application.yml"), golden,
        MentorGoldenCase.load(golden), new MentorPromptBuilder(),
        Map.of("ollama", "https://EVAL-OLLAMA.example.test:443/api/"),
        artifacts.bootJar(), artifacts.sharedArtifact(), artifacts.dependencyGraph(),
        artifacts.currentDependencyGraph(), artifacts.bootLibraryGraph(),
        artifacts.gradleProperties());
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(inputs);
    Path manifestPath = temp.resolve("mentor-release-eval-manifest-v3.json");
    manifest.write(manifestPath);
    MentorEvalEvidence evaluation = MentorEvalEvidence.passing(
        manifest,
        MentorReleaseEvalManifest.sha256(manifestPath),
        NOW.minusSeconds(10),
        List.of(
            new MentorEvalEvidence.ModelResult(
                "primary", "ollama", "qwen2.5:3b", 1.0, 0.95, 10),
            new MentorEvalEvidence.ModelResult(
                "fallback", "claude", "claude-sonnet-4-6", 1.0, 0.96, 11)));
    Path evaluationPath = temp.resolve("mentor-release-eval-evidence-v3.json");
    evaluation.write(evaluationPath);
    Path candidatePath = temp.resolve("candidate-spec.json");
    LinkedHashMap<String, Object> candidate = new LinkedHashMap<>();
    candidate.put("document_type", "candidate-spec");
    candidate.put("release_id", "ms-20260817-et9");
    candidate.put("gitops", Map.of(
        "repository", "DevPathAi/devpath-gitops",
        "base_sha", GITOPS));
    candidate.put("services", Map.of(
        "devpath-ai-svc", Map.of(
            "repository", "DevPathAi/devpath-ai-svc",
            "source_sha", SOURCE)));
    candidate.put("ai_release_eval_config", Map.of(
        "primary_model", "qwen2.5:3b",
        "fallback_models", List.of("claude-sonnet-4-6"),
        "prompt_sha256", manifest.promptSha256(),
        "fixture_revision", manifest.fixtureRevision(),
        "fixture_sha256", manifest.fixtureSha256(),
        "rendered_config_sha256", manifest.renderedConfigSha256(),
        "ollama_endpoint_sha256", MissionSpineReleaseEvalEvidence.ollamaEndpointSha256(
            manifest.models().getFirst().evaluationEndpoint())));
    Files.writeString(candidatePath, MAPPER.writeValueAsString(candidate) + "\n");
    String candidateSha = MentorReleaseEvalManifest.sha256(candidatePath);
    MissionSpineReleaseEvalEvidence.ProtectedApproval approval =
        new MissionSpineReleaseEvalEvidence.ProtectedApproval(
            "mission-spine-ai-release-eval",
            91,
            "Run AI release evaluation",
            "independent-reviewer",
            21,
            "2026-08-17T11:59:00Z");
    MissionSpineReleaseEvalEvidence.Context context =
        new MissionSpineReleaseEvalEvidence.Context(
            candidateSha, 501, 1, SOURCE, GITOPS, candidatePath, inputs,
            manifestPath, evaluationPath, approval,
            Clock.fixed(NOW, ZoneOffset.UTC));
    return new Fixture(
        inputs, manifestPath, evaluationPath, candidatePath, candidateSha, context);
  }

  private MissionSpineReleaseEvalEvidence.Context candidateContext(
      Fixture fixture, String candidateSha) {
    MissionSpineReleaseEvalEvidence.Context original = fixture.context();
    return new MissionSpineReleaseEvalEvidence.Context(
        candidateSha,
        original.producerRunId(),
        original.producerRunAttempt(),
        original.aiSourceSha(),
        original.gitopsSourceSha(),
        fixture.candidate(),
        original.evalInputs(),
        original.manifestPath(),
        original.evaluationPath(),
        original.approval(),
        original.clock());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> value, String key) {
    return (Map<String, Object>) value.get(key);
  }

  private record Fixture(
      MentorReleaseEvalManifest.Inputs inputs,
      Path manifest,
      Path evaluation,
      Path candidate,
      String candidateSha,
      MissionSpineReleaseEvalEvidence.Context context) {}
}
