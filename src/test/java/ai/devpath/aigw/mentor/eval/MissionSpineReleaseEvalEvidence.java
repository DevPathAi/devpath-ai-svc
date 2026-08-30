package ai.devpath.aigw.mentor.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sanitized Mission Spine adapter for the existing hash-bound ET9 evaluation.
 *
 * <p>This class cannot manufacture scores. It reopens and validates the exact release manifest
 * and live evaluation evidence before exposing the small GitOps payload.</p>
 */
record MissionSpineReleaseEvalEvidence(
    String candidateSpecSha256,
    String status,
    long producerRunId,
    long producerRunAttempt,
    String aiSourceSha,
    String gitopsSourceSha,
    String runtimePrimaryModel,
    List<String> runtimeFallbackModels,
    String developmentModel,
    String tuningRevision,
    String tuningSha256,
    String promptSha256,
    String fixtureRevision,
    String fixtureSha256,
    String renderedConfigSha256,
    String ollamaEndpointSha256,
    double hardInvariantsPercent,
    double usefulnessPercent,
    double baselineDeltaPoints,
    String approvalEnvironment,
    long approvalEnvironmentId,
    String approvalJobName,
    String approvedBy,
    long approvedById,
    String approvalEffectiveAt) {

  private static final String STATUS = "passed";
  private static final String ENVIRONMENT = "mission-spine-ai-release-eval";
  private static final String JOB = "Run AI release evaluation";
  private static final Pattern SHA40 = Pattern.compile("(?!0{40})[0-9a-f]{40}");
  private static final Pattern SHA64 = Pattern.compile("(?!0{64})[0-9a-f]{64}");
  private static final Pattern SAFE_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:/-]{1,127}");
  private static final Pattern LOGIN =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
  private static final Pattern UTC = Pattern.compile(
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final ObjectReader STRICT_MAP_READER = MAPPER.readerFor(Map.class)
      .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final List<String> AI_CONFIG_KEYS = List.of(
      "runtime_primary_model",
      "runtime_fallback_models",
      "development_model",
      "tuning_revision",
      "tuning_sha256",
      "prompt_sha256",
      "fixture_revision",
      "fixture_sha256",
      "rendered_config_sha256",
      "ollama_endpoint_sha256");
  private static final List<String> KEYS = List.of(
      "candidate_spec_sha256",
      "status",
      "producer_run_id",
      "producer_run_attempt",
      "ai_source_sha",
      "gitops_source_sha",
      "runtime_primary_model",
      "runtime_fallback_models",
      "development_model",
      "tuning_revision",
      "tuning_sha256",
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

  static MissionSpineReleaseEvalEvidence create(Context context) {
    validateContext(context);
    MentorReleaseEvalManifest manifest = validateCandidate(
        context.candidatePath(), context.candidateSpecSha256(), context.aiSourceSha(),
        context.gitopsSourceSha(), context.evalInputs(), context.manifestPath());
    MentorEvalEvidence evaluation = MentorEvalEvidence.read(context.evaluationPath());
    evaluation.validate(
        manifest,
        MentorReleaseEvalManifest.sha256(context.manifestPath()),
        context.clock());
    if (!manifest.sourceRevision().equals(context.aiSourceSha())
        || !manifest.gitOpsRevision().equals(context.gitopsSourceSha())) {
      throw new IllegalArgumentException("release source identity does not match the ET9 manifest");
    }
    if (manifest.models().size() != 1
        || !"development-eval".equals(manifest.models().getFirst().role())
        || !"ollama".equals(manifest.models().getFirst().provider())) {
      throw new IllegalArgumentException(
          "development release evaluation must use exactly one local Ollama model");
    }

    double hardPercent = percent(evaluation.hardInvariantRate());
    double usefulnessPercent = percent(evaluation.qualityRate());
    double baselineDelta = roundedPoints(
        evaluation.qualityRate() - manifest.baselineScore());
    ProtectedApproval approval = context.approval();
    MissionSpineReleaseEvalEvidence result = new MissionSpineReleaseEvalEvidence(
        context.candidateSpecSha256(), STATUS, context.producerRunId(),
        context.producerRunAttempt(), context.aiSourceSha(), context.gitopsSourceSha(),
        manifest.runtimePrimaryModel(), manifest.runtimeFallbackModels(),
        manifest.models().getFirst().modelId(), manifest.tuningRevision(),
        manifest.tuningSha256(), manifest.promptSha256(),
        manifest.fixtureRevision(), manifest.fixtureSha256(), manifest.renderedConfigSha256(),
        ollamaEndpointSha256(manifest.models().getFirst().evaluationEndpoint()),
        hardPercent, usefulnessPercent, baselineDelta, approval.approvalEnvironment(),
        approval.approvalEnvironmentId(), approval.approvalJobName(), approval.approvedBy(),
        approval.approvedById(), approval.approvalEffectiveAt());
    result.validateValues();
    return result;
  }

  static void validate(Path path, Context context) {
    MissionSpineReleaseEvalEvidence expected = create(context);
    MissionSpineReleaseEvalEvidence actual = read(path);
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Mission Spine release evidence does not match ET9 inputs");
    }
  }

  static MentorReleaseEvalManifest validateCandidate(
      Path candidatePath,
      String candidateSpecSha256,
      String aiSourceSha,
      String gitopsSourceSha,
      MentorReleaseEvalManifest.Inputs evalInputs,
      Path manifestPath) {
    if (!SHA64.matcher(candidateSpecSha256).matches()
        || !SHA40.matcher(aiSourceSha).matches()
        || !SHA40.matcher(gitopsSourceSha).matches()) {
      throw new IllegalArgumentException("candidate release identity is invalid");
    }
    if (!Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(candidatePath)) {
      throw new IllegalArgumentException("candidate must be one regular non-link file");
    }
    byte[] bytes;
    Map<String, Object> candidate;
    try {
      bytes = Files.readAllBytes(candidatePath);
      if (bytes.length < 2 || bytes.length > 256 * 1024) {
        throw new IllegalArgumentException("candidate byte size is invalid");
      }
      if (!candidateSpecSha256.equals(MentorReleaseEvalManifest.sha256(candidatePath))) {
        throw new IllegalArgumentException("candidate raw SHA-256 mismatch");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = STRICT_MAP_READER.readValue(bytes);
      candidate = parsed;
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("candidate JSON is missing or invalid", failure);
    }

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.read(manifestPath);
    manifest.validate(evalInputs);
    Map<String, Object> gitops = object(candidate, "gitops");
    Map<String, Object> services = object(candidate, "services");
    Map<String, Object> aiService = object(services, "devpath-ai-svc");
    Map<String, Object> config = object(candidate, "ai_release_eval_config");
    if (!Set.copyOf(config.keySet()).equals(Set.copyOf(AI_CONFIG_KEYS))
        || config.size() != AI_CONFIG_KEYS.size()) {
      throw new IllegalArgumentException("candidate AI config exact key set mismatch");
    }
    if (!"candidate-spec".equals(string(candidate, "document_type"))
        || !evalInputs.releaseId().equals(string(candidate, "release_id"))
        || !"DevPathAi/devpath-gitops".equals(string(gitops, "repository"))
        || !gitopsSourceSha.equals(string(gitops, "base_sha"))
        || !"DevPathAi/devpath-ai-svc".equals(string(aiService, "repository"))
        || !aiSourceSha.equals(string(aiService, "source_sha"))
        || !manifest.runtimePrimaryModel().equals(string(config, "runtime_primary_model"))
        || !manifest.runtimeFallbackModels().equals(strings(config, "runtime_fallback_models"))
        || !manifest.models().getFirst().modelId().equals(
            string(config, "development_model"))
        || !manifest.tuningRevision().equals(string(config, "tuning_revision"))
        || !manifest.tuningSha256().equals(string(config, "tuning_sha256"))
        || !manifest.promptSha256().equals(string(config, "prompt_sha256"))
        || !manifest.fixtureRevision().equals(string(config, "fixture_revision"))
        || !manifest.fixtureSha256().equals(string(config, "fixture_sha256"))
        || !manifest.renderedConfigSha256().equals(
            string(config, "rendered_config_sha256"))
        || !ollamaEndpointSha256(manifest.models().getFirst().evaluationEndpoint()).equals(
            string(config, "ollama_endpoint_sha256"))) {
      throw new IllegalArgumentException("candidate does not bind exact ET9 release inputs");
    }
    return manifest;
  }

  void write(Path path) {
    validateValues();
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      byte[] bytes = (MAPPER.writeValueAsString(toMap()) + "\n")
          .getBytes(StandardCharsets.UTF_8);
      Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (java.nio.file.FileAlreadyExistsException exists) {
      throw new IllegalArgumentException("release evidence output already exists", exists);
    } catch (Exception failure) {
      throw new IllegalStateException("release evidence write failed", failure);
    }
  }

  private static MissionSpineReleaseEvalEvidence read(Path path) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length < 2 || bytes.length > 256 * 1024
          || bytes[bytes.length - 1] != '\n') {
        throw new IllegalArgumentException("release evidence bytes are not bounded canonical JSON");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> value = STRICT_MAP_READER.readValue(bytes);
      if (!new ArrayList<>(value.keySet()).equals(KEYS)) {
        throw new IllegalArgumentException("release evidence keys/order are not exact");
      }
      MissionSpineReleaseEvalEvidence evidence = new MissionSpineReleaseEvalEvidence(
          string(value, "candidate_spec_sha256"),
          string(value, "status"),
          integer(value, "producer_run_id"),
          integer(value, "producer_run_attempt"),
          string(value, "ai_source_sha"),
          string(value, "gitops_source_sha"),
          string(value, "runtime_primary_model"),
          strings(value, "runtime_fallback_models"),
          string(value, "development_model"),
          string(value, "tuning_revision"),
          string(value, "tuning_sha256"),
          string(value, "prompt_sha256"),
          string(value, "fixture_revision"),
          string(value, "fixture_sha256"),
          string(value, "rendered_config_sha256"),
          string(value, "ollama_endpoint_sha256"),
          number(value, "hard_invariants_percent"),
          number(value, "usefulness_percent"),
          number(value, "baseline_delta_points"),
          string(value, "approval_environment"),
          integer(value, "approval_environment_id"),
          string(value, "approval_job_name"),
          string(value, "approved_by"),
          integer(value, "approved_by_id"),
          string(value, "approval_effective_at"));
      evidence.validateValues();
      return evidence;
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("release evidence is missing or invalid", failure);
    }
  }

  private LinkedHashMap<String, Object> toMap() {
    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
    value.put("candidate_spec_sha256", candidateSpecSha256);
    value.put("status", status);
    value.put("producer_run_id", producerRunId);
    value.put("producer_run_attempt", producerRunAttempt);
    value.put("ai_source_sha", aiSourceSha);
    value.put("gitops_source_sha", gitopsSourceSha);
    value.put("runtime_primary_model", runtimePrimaryModel);
    value.put("runtime_fallback_models", runtimeFallbackModels);
    value.put("development_model", developmentModel);
    value.put("tuning_revision", tuningRevision);
    value.put("tuning_sha256", tuningSha256);
    value.put("prompt_sha256", promptSha256);
    value.put("fixture_revision", fixtureRevision);
    value.put("fixture_sha256", fixtureSha256);
    value.put("rendered_config_sha256", renderedConfigSha256);
    value.put("ollama_endpoint_sha256", ollamaEndpointSha256);
    value.put("hard_invariants_percent", hardInvariantsPercent);
    value.put("usefulness_percent", usefulnessPercent);
    value.put("baseline_delta_points", baselineDeltaPoints);
    value.put("approval_environment", approvalEnvironment);
    value.put("approval_environment_id", approvalEnvironmentId);
    value.put("approval_job_name", approvalJobName);
    value.put("approved_by", approvedBy);
    value.put("approved_by_id", approvedById);
    value.put("approval_effective_at", approvalEffectiveAt);
    return value;
  }

  private void validateValues() {
    if (!SHA64.matcher(candidateSpecSha256).matches()
        || !STATUS.equals(status)
        || producerRunId < 1
        || producerRunAttempt != 1
        || !SHA40.matcher(aiSourceSha).matches()
        || !SHA40.matcher(gitopsSourceSha).matches()
        || !SAFE_ID.matcher(runtimePrimaryModel).matches()
        || runtimeFallbackModels == null
        || runtimeFallbackModels.isEmpty()
        || runtimeFallbackModels.stream().anyMatch(model -> !SAFE_ID.matcher(model).matches())
        || new LinkedHashSet<>(runtimeFallbackModels).size() != runtimeFallbackModels.size()
        || runtimeFallbackModels.contains(runtimePrimaryModel)
        || !SAFE_ID.matcher(developmentModel).matches()
        || !SAFE_ID.matcher(tuningRevision).matches()
        || !SHA64.matcher(tuningSha256).matches()
        || !SHA64.matcher(promptSha256).matches()
        || !SAFE_ID.matcher(fixtureRevision).matches()
        || !SHA64.matcher(fixtureSha256).matches()
        || !SHA64.matcher(renderedConfigSha256).matches()
        || !SHA64.matcher(ollamaEndpointSha256).matches()
        || hardInvariantsPercent != 100.0
        || !bounded(usefulnessPercent, 90.0, 100.0)
        || !bounded(baselineDeltaPoints, -5.0, 100.0)
        || !ENVIRONMENT.equals(approvalEnvironment)
        || approvalEnvironmentId < 1
        || !JOB.equals(approvalJobName)
        || !LOGIN.matcher(approvedBy).matches()
        || approvedById < 1
        || !validUtc(approvalEffectiveAt)) {
      throw new IllegalArgumentException("Mission Spine release evidence values are invalid");
    }
  }

  private static void validateContext(Context context) {
    if (context == null || context.candidatePath() == null || context.evalInputs() == null
        || context.manifestPath() == null
        || context.evaluationPath() == null || context.approval() == null
        || context.clock() == null) {
      throw new IllegalArgumentException("release evidence context is incomplete");
    }
    if (!SHA64.matcher(context.candidateSpecSha256()).matches()
        || context.producerRunId() < 1 || context.producerRunAttempt() != 1
        || !SHA40.matcher(context.aiSourceSha()).matches()
        || !SHA40.matcher(context.gitopsSourceSha()).matches()) {
      throw new IllegalArgumentException("release evidence context identity is invalid");
    }
    context.approval().validate();
  }

  private static double percent(double rate) {
    return rounded(rate * 100.0);
  }

  static String ollamaEndpointSha256(String value) {
    return MentorReleaseEvalManifest.ollamaEvaluationEndpointSha256(value);
  }

  private static double roundedPoints(double rateDelta) {
    return rounded(rateDelta * 100.0);
  }

  private static double rounded(double value) {
    return Math.rint(value * 1_000_000.0) / 1_000_000.0;
  }

  private static boolean bounded(double value, double minimum, double maximum) {
    return Double.isFinite(value) && value >= minimum && value <= maximum;
  }

  private static boolean validUtc(String value) {
    if (value == null || !UTC.matcher(value).matches()) return false;
    try {
      Instant.parse(value);
      return true;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private static String string(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (!(raw instanceof String result)) {
      throw new IllegalArgumentException(key + " must be a string");
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (!(raw instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(key + " must be an object");
    }
    return (Map<String, Object>) raw;
  }

  private static long integer(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer
        || raw instanceof Long)) {
      throw new IllegalArgumentException(key + " must be an integer");
    }
    return ((Number) raw).longValue();
  }

  private static double number(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (!(raw instanceof Number number) || raw instanceof Boolean) {
      throw new IllegalArgumentException(key + " must be a number");
    }
    double result = number.doubleValue();
    if (!Double.isFinite(result)) {
      throw new IllegalArgumentException(key + " must be finite");
    }
    return result;
  }

  private static List<String> strings(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException(key + " must be an array");
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String text)) {
        throw new IllegalArgumentException(key + " values must be strings");
      }
      result.add(text);
    }
    return List.copyOf(result);
  }

  record Context(
      String candidateSpecSha256,
      long producerRunId,
      long producerRunAttempt,
      String aiSourceSha,
      String gitopsSourceSha,
      Path candidatePath,
      MentorReleaseEvalManifest.Inputs evalInputs,
      Path manifestPath,
      Path evaluationPath,
      ProtectedApproval approval,
      Clock clock) {}

  record ProtectedApproval(
      String approvalEnvironment,
      long approvalEnvironmentId,
      String approvalJobName,
      String approvedBy,
      long approvedById,
      String approvalEffectiveAt) {

    private static final List<String> KEYS = List.of(
        "approval_environment",
        "approval_environment_id",
        "approval_job_name",
        "approved_by",
        "approved_by_id",
        "approval_effective_at");

    static ProtectedApproval read(Path path) {
      try {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 2 || bytes.length > 16 * 1024) {
          throw new IllegalArgumentException("protected approval is not bounded");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> value = STRICT_MAP_READER.readValue(bytes);
        if (!new ArrayList<>(value.keySet()).equals(KEYS)) {
          throw new IllegalArgumentException("protected approval keys/order are not exact");
        }
        ProtectedApproval approval = new ProtectedApproval(
            string(value, "approval_environment"),
            integer(value, "approval_environment_id"),
            string(value, "approval_job_name"),
            string(value, "approved_by"),
            integer(value, "approved_by_id"),
            string(value, "approval_effective_at"));
        approval.validate();
        return approval;
      } catch (IllegalArgumentException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalArgumentException("protected approval is missing or invalid", failure);
      }
    }

    private void validate() {
      if (!ENVIRONMENT.equals(approvalEnvironment)
          || approvalEnvironmentId < 1
          || !JOB.equals(approvalJobName)
          || !LOGIN.matcher(approvedBy).matches()
          || approvedById < 1
          || !validUtc(approvalEffectiveAt)) {
        throw new IllegalArgumentException("protected approval values are invalid");
      }
    }
  }
}
