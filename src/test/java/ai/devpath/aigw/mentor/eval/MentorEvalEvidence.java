package ai.devpath.aigw.mentor.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Safe, hash-bound release evidence. It intentionally contains no prompts, answers, or tokens. */
record MentorEvalEvidence(
    String schemaVersion,
    String manifestSha256,
    String releaseId,
    String sourceRevision,
    String gitOpsRevision,
    String renderedConfigSha256,
    String sharedCoordinate,
    String sharedArtifactSha256,
    String bootJarSha256,
    String runtimeDependencyGraphSha256,
    String bootLibraryGraphSha256,
    String promptSha256,
    String fixtureRevision,
    String fixtureSha256,
    String generatedAt,
    String result,
    double hardInvariantRate,
    double qualityRate,
    double requiredQualityRate,
    List<ModelResult> models) {

  private static final String SCHEMA = "mentor-release-eval-evidence/v4";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  static MentorEvalEvidence passing(MentorReleaseEvalManifest manifest, String manifestSha256,
      Instant generatedAt, List<ModelResult> results) {
    MentorEvalEvidence evidence = evaluated(manifest, manifestSha256, generatedAt, results);
    if (!"PASS".equals(evidence.result())) {
      throw new IllegalArgumentException("passing evidence does not satisfy release thresholds");
    }
    return evidence;
  }

  static MentorEvalEvidence evaluated(MentorReleaseEvalManifest manifest, String manifestSha256,
      Instant generatedAt, List<ModelResult> results) {
    if (results == null || results.isEmpty()) {
      throw new IllegalArgumentException("model evidence is required");
    }
    double hardRate = results.stream().mapToDouble(ModelResult::hardInvariantRate).min().orElse(0);
    double qualityRate = results.stream().mapToDouble(ModelResult::qualityRate).min().orElse(0);
    boolean passed = results.size() == manifest.models().size()
        && results.stream().allMatch(result ->
            result.hardInvariantRate() >= manifest.hardInvariantMinimum()
                && result.qualityRate() >= manifest.requiredQualityRate());
    return new MentorEvalEvidence(
        SCHEMA, manifestSha256, manifest.releaseId(), manifest.sourceRevision(),
        manifest.gitOpsRevision(), manifest.renderedConfigSha256(), manifest.sharedCoordinate(),
        manifest.sharedArtifactSha256(), manifest.bootJarSha256(),
        manifest.runtimeDependencyGraphSha256(), manifest.bootLibraryGraphSha256(),
        manifest.promptSha256(),
        manifest.fixtureRevision(), manifest.fixtureSha256(), generatedAt.toString(),
        passed ? "PASS" : "FAIL",
        hardRate, qualityRate, manifest.requiredQualityRate(), List.copyOf(results));
  }

  void validate(MentorReleaseEvalManifest manifest, String actualManifestSha256, Clock clock) {
    if (!SCHEMA.equals(schemaVersion)
        || !actualManifestSha256.equals(manifestSha256)
        || !manifest.releaseId().equals(releaseId)
        || !manifest.sourceRevision().equals(sourceRevision)
        || !manifest.gitOpsRevision().equals(gitOpsRevision)
        || !manifest.renderedConfigSha256().equals(renderedConfigSha256)
        || !manifest.sharedCoordinate().equals(sharedCoordinate)
        || !manifest.sharedArtifactSha256().equals(sharedArtifactSha256)
        || !manifest.bootJarSha256().equals(bootJarSha256)
        || !manifest.runtimeDependencyGraphSha256().equals(runtimeDependencyGraphSha256)
        || !manifest.bootLibraryGraphSha256().equals(bootLibraryGraphSha256)
        || !manifest.promptSha256().equals(promptSha256)
        || !manifest.fixtureRevision().equals(fixtureRevision)
        || !manifest.fixtureSha256().equals(fixtureSha256)
        || !"PASS".equals(result)
        || !validRate(hardInvariantRate)
        || !validRate(qualityRate)
        || !validRate(requiredQualityRate)) {
      throw new IllegalArgumentException("release eval evidence does not match manifest");
    }
    Instant produced;
    try {
      produced = Instant.parse(generatedAt);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("release eval evidence timestamp is invalid");
    }
    Duration age = Duration.between(produced, clock.instant());
    if (age.isNegative() || age.compareTo(Duration.ofSeconds(manifest.evidenceMaxAgeSeconds())) > 0) {
      throw new IllegalArgumentException("release eval evidence is stale");
    }
    if (models.size() != manifest.models().size()) {
      throw new IllegalArgumentException("release eval evidence model count mismatch");
    }
    for (int index = 0; index < models.size(); index++) {
      ModelResult result = models.get(index);
      MentorReleaseEvalManifest.Model expected = manifest.models().get(index);
      if (!expected.role().equals(result.role())
          || !expected.provider().equals(result.provider())
          || !expected.modelId().equals(result.modelId())
          || !validRate(result.hardInvariantRate())
          || !validRate(result.qualityRate())
          || result.latencyMs() < 0
          || result.hardInvariantRate() < manifest.hardInvariantMinimum()
          || result.qualityRate() < manifest.requiredQualityRate()) {
        throw new IllegalArgumentException("release eval evidence model result is invalid");
      }
    }
    if (hardInvariantRate < manifest.hardInvariantMinimum()
        || qualityRate < manifest.requiredQualityRate()
        || Math.abs(requiredQualityRate - manifest.requiredQualityRate()) > 0.000_001) {
      throw new IllegalArgumentException("release eval evidence thresholds are not satisfied");
    }
  }

  private static boolean validRate(double value) {
    return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
  }

  MentorEvalEvidence withManifestSha256(String value) {
    return new MentorEvalEvidence(schemaVersion, value, releaseId, sourceRevision, gitOpsRevision,
        renderedConfigSha256, sharedCoordinate, sharedArtifactSha256, bootJarSha256,
        runtimeDependencyGraphSha256, bootLibraryGraphSha256, promptSha256, fixtureRevision,
        fixtureSha256,
        generatedAt, result,
        hardInvariantRate, qualityRate, requiredQualityRate, models);
  }

  void write(Path path) {
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.write(path, MAPPER.writeValueAsBytes(this));
    } catch (Exception failure) {
      throw new IllegalStateException("release eval evidence write failed", failure);
    }
  }

  static MentorEvalEvidence read(Path path) {
    try {
      return MAPPER.readValue(Files.readAllBytes(path), MentorEvalEvidence.class);
    } catch (Exception failure) {
      throw new IllegalArgumentException("release eval evidence is missing or invalid", failure);
    }
  }

  record ModelResult(String role, String provider, String modelId,
                     double hardInvariantRate, double qualityRate, long latencyMs) {}
}
