package ai.devpath.aigw.mentor.eval;

import ai.devpath.aigw.mentor.MentorInput;
import ai.devpath.aigw.mentor.MentorPromptBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import tools.jackson.databind.json.JsonMapper;

/** Versioned immutable contract for one exact release-model evaluation. */
record MentorReleaseEvalManifest(
    String schemaVersion,
    String releaseId,
    String sourceRevision,
    String gitOpsRevision,
    String renderedConfigSha256,
    String sharedCoordinate,
    String sharedArtifactSha256,
    String bootJarSha256,
    String runtimeDependencyGraphSha256,
    String bootLibraryGraphSha256,
    List<Model> models,
    String promptSha256,
    String fixtureRevision,
    String fixtureSha256,
    double baselineScore,
    double hardInvariantMinimum,
    double qualityMinimum,
    double maxBaselineDrop,
    long evidenceMaxAgeSeconds) {

  static final String SCHEMA = "mentor-release-eval/v3";
  static final String FIXTURE_REVISION = "mentor-golden-v2";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40,64}");
  private static final Pattern ENV_DEFAULT =
      Pattern.compile("\\$\\{([A-Z0-9_]+):([^}]+)}");
  private static final Pattern ENV_NAME =
      Pattern.compile("^\\s*-\\s+name:\\s*([A-Z0-9_]+)\\s*$");
  private static final Pattern ENV_VALUE =
      Pattern.compile("^\\s+value:\\s*[\"']?([^\"']+?)[\"']?\\s*$");

  static MentorReleaseEvalManifest create(Inputs inputs) {
    inputs.validateIdentifiers();
    List<Model> models = runtimeModels(
        inputs.renderedConfig(), inputs.applicationConfig(), inputs.evaluationEndpoints());
    if (models.size() != 2) {
      throw new IllegalArgumentException("release evaluation requires primary and fallback models");
    }
    ReleaseArtifacts artifacts = releaseArtifacts(inputs);
    return new MentorReleaseEvalManifest(
        SCHEMA,
        inputs.releaseId(),
        inputs.sourceRevision(),
        inputs.gitOpsRevision(),
        sha256(inputs.renderedConfig()),
        artifacts.sharedCoordinate(),
        artifacts.sharedArtifactSha256(),
        artifacts.bootJarSha256(),
        artifacts.runtimeDependencyGraphSha256(),
        artifacts.bootLibraryGraphSha256(),
        models,
        promptSuiteSha256(inputs.prompts(), inputs.cases()),
        FIXTURE_REVISION,
        sha256(inputs.fixture()),
        0.92,
        1.0,
        0.90,
        0.05,
        3600);
  }

  void validate(Inputs inputs) {
    MentorReleaseEvalManifest expected = create(inputs);
    if (!equals(expected)) {
      throw new IllegalArgumentException("release eval manifest does not match exact source/config");
    }
  }

  void write(Path path) {
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(path, MAPPER.writeValueAsBytes(this));
    } catch (Exception failure) {
      throw new IllegalStateException("release eval manifest write failed", failure);
    }
  }

  static MentorReleaseEvalManifest read(Path path) {
    try {
      return MAPPER.readValue(Files.readAllBytes(path), MentorReleaseEvalManifest.class);
    } catch (Exception failure) {
      throw new IllegalArgumentException("release eval manifest is missing or invalid", failure);
    }
  }

  MentorReleaseEvalManifest withFixtureSha256(String value) {
    return copy(models, promptSha256, value, sharedArtifactSha256, bootJarSha256,
        runtimeDependencyGraphSha256, bootLibraryGraphSha256);
  }

  MentorReleaseEvalManifest withPromptSha256(String value) {
    return copy(models, value, fixtureSha256, sharedArtifactSha256, bootJarSha256,
        runtimeDependencyGraphSha256, bootLibraryGraphSha256);
  }

  MentorReleaseEvalManifest withModels(List<Model> value) {
    return copy(value, promptSha256, fixtureSha256, sharedArtifactSha256, bootJarSha256,
        runtimeDependencyGraphSha256, bootLibraryGraphSha256);
  }

  MentorReleaseEvalManifest withSharedCoordinate(String value) {
    return new MentorReleaseEvalManifest(schemaVersion, releaseId, sourceRevision, gitOpsRevision,
        renderedConfigSha256, value, sharedArtifactSha256, bootJarSha256,
        runtimeDependencyGraphSha256, bootLibraryGraphSha256, models, promptSha256,
        fixtureRevision, fixtureSha256,
        baselineScore, hardInvariantMinimum, qualityMinimum, maxBaselineDrop,
        evidenceMaxAgeSeconds);
  }

  MentorReleaseEvalManifest withSharedArtifactSha256(String value) {
    return copy(models, promptSha256, fixtureSha256, value, bootJarSha256,
        runtimeDependencyGraphSha256, bootLibraryGraphSha256);
  }

  MentorReleaseEvalManifest withBootJarSha256(String value) {
    return copy(models, promptSha256, fixtureSha256, sharedArtifactSha256, value,
        runtimeDependencyGraphSha256, bootLibraryGraphSha256);
  }

  MentorReleaseEvalManifest withRuntimeDependencyGraphSha256(String value) {
    return copy(models, promptSha256, fixtureSha256, sharedArtifactSha256, bootJarSha256,
        value, bootLibraryGraphSha256);
  }

  MentorReleaseEvalManifest withBootLibraryGraphSha256(String value) {
    return copy(models, promptSha256, fixtureSha256, sharedArtifactSha256, bootJarSha256,
        runtimeDependencyGraphSha256, value);
  }

  private MentorReleaseEvalManifest copy(List<Model> modelValue, String promptValue,
      String fixtureValue, String sharedArtifactValue, String bootJarValue,
      String dependencyGraphValue, String bootLibraryGraphValue) {
    return new MentorReleaseEvalManifest(schemaVersion, releaseId, sourceRevision, gitOpsRevision,
        renderedConfigSha256, sharedCoordinate, sharedArtifactValue, bootJarValue,
        dependencyGraphValue, bootLibraryGraphValue, List.copyOf(modelValue), promptValue,
        fixtureRevision, fixtureValue,
        baselineScore, hardInvariantMinimum, qualityMinimum, maxBaselineDrop,
        evidenceMaxAgeSeconds);
  }

  double requiredQualityRate() {
    return Math.max(qualityMinimum, baselineScore - maxBaselineDrop);
  }

  void validateCredentials(Map<String, String> environment) {
    for (Model model : models) {
      if (model.credentialEnv() == null) {
        continue;
      }
      String value = environment.get(model.credentialEnv());
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            model.credentialEnv() + " is required for release model evaluation");
      }
    }
  }

  static String sha256(Path path) {
    try {
      return sha256(Files.readAllBytes(path));
    } catch (Exception failure) {
      throw new IllegalArgumentException("release eval input cannot be hashed", failure);
    }
  }

  static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static String promptSuiteSha256(MentorPromptBuilder prompts,
      List<MentorGoldenCase> cases) {
    StringBuilder suite = new StringBuilder(prompts.systemPrompt());
    for (MentorGoldenCase goldenCase : cases) {
      suite.append('\n').append(goldenCase.caseId()).append('\n')
          .append(prompts.userContent(new MentorInput(
              goldenCase.question(), goldenCase.context(), goldenCase.referenceDocs())));
    }
    return sha256(suite.toString());
  }

  private static ReleaseArtifacts releaseArtifacts(Inputs inputs) {
    Properties properties = new Properties();
    try (java.io.InputStream stream = Files.newInputStream(inputs.gradleProperties())) {
      properties.load(stream);
    } catch (Exception failure) {
      throw new IllegalArgumentException("Gradle release coordinate is missing", failure);
    }
    String version = properties.getProperty("devpathSharedVersion");
    if (version == null || version.isBlank() || version.endsWith("SNAPSHOT")
        || !version.matches("[0-9A-Za-z._-]+")) {
      throw new IllegalArgumentException("Shared release coordinate is not immutable");
    }
    String coordinate = "ai.devpath:devpath-shared:" + version;
    String sharedHash = sha256(inputs.sharedArtifact());
    String graphHash = sha256(inputs.dependencyGraph());
    String currentGraphHash = sha256(inputs.currentDependencyGraph());
    String bootGraphHash = sha256(inputs.bootLibraryGraph());
    if (!graphHash.equals(currentGraphHash)) {
      throw new IllegalArgumentException(
          "evaluated runtime dependency graph differs from the release build");
    }
    String expectedSharedFile = "devpath-shared-" + version + ".jar";
    if (!expectedSharedFile.equals(inputs.sharedArtifact().getFileName().toString())) {
      throw new IllegalArgumentException("Shared artifact filename does not match its coordinate");
    }
    String expectedLine = coordinate + "|" + expectedSharedFile + "|" + sharedHash;
    List<String> graphLines;
    try {
      graphLines = Files.readAllLines(inputs.dependencyGraph(), StandardCharsets.UTF_8);
    } catch (Exception failure) {
      throw new IllegalArgumentException("release dependency graph is missing", failure);
    }
    if (graphLines.stream().filter(line -> line.startsWith("ai.devpath:devpath-shared:"))
        .count() != 1 || !graphLines.contains(expectedLine)) {
      throw new IllegalArgumentException(
          "release dependency graph does not contain the exact Shared artifact");
    }

    if (!graphLines.equals(graphLines.stream().sorted().toList())) {
      throw new IllegalArgumentException("release runtime dependency graph is not sorted");
    }
    for (String line : graphLines) {
      String[] fields = line.split("\\|", -1);
      if (fields.length != 3 || !fields[0].matches("[^:|]+:[^:|]+:[^|]+")
          || fields[1].isBlank() || !fields[2].matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("release runtime dependency graph is malformed");
      }
    }

    List<String> bootGraphLines;
    try {
      bootGraphLines = Files.readAllLines(inputs.bootLibraryGraph(), StandardCharsets.UTF_8);
    } catch (Exception failure) {
      throw new IllegalArgumentException("release boot library graph is missing", failure);
    }
    if (!bootGraphLines.equals(bootGraphLines.stream().sorted().toList())) {
      throw new IllegalArgumentException("release boot library graph is not sorted");
    }
    Map<String, String> bootLibraryHashes = new LinkedHashMap<>();
    for (String line : bootGraphLines) {
      String[] fields = line.split("\\|", -1);
      if (fields.length != 2 || fields[0].isBlank()
          || !fields[1].matches("[0-9a-f]{64}")
          || bootLibraryHashes.putIfAbsent(fields[0], fields[1]) != null) {
        throw new IllegalArgumentException("release boot library graph is malformed or ambiguous");
      }
    }

    String nestedName = "BOOT-INF/lib/" + expectedSharedFile;
    try (ZipFile bootJar = new ZipFile(inputs.bootJar().toFile())) {
      List<? extends java.util.zip.ZipEntry> dependencyEntries = bootJar.stream()
          .filter(entry -> !entry.isDirectory() && entry.getName().startsWith("BOOT-INF/lib/"))
          .toList();
      Set<String> nestedFiles = dependencyEntries.stream()
          .map(entry -> entry.getName().substring("BOOT-INF/lib/".length()))
          .collect(java.util.stream.Collectors.toSet());
      if (nestedFiles.size() != dependencyEntries.size()
          || !nestedFiles.equals(bootLibraryHashes.keySet())) {
        throw new IllegalArgumentException(
            "release bootJar libraries differ from the boot library graph");
      }
      for (java.util.zip.ZipEntry entry : dependencyEntries) {
        String filename = entry.getName().substring("BOOT-INF/lib/".length());
        byte[] nested = bootJar.getInputStream(entry).readAllBytes();
        if (!bootLibraryHashes.get(filename).equals(sha256(nested))) {
          throw new IllegalArgumentException(
              "release bootJar library hash differs from the boot library graph");
        }
      }
      List<? extends java.util.zip.ZipEntry> sharedEntries = dependencyEntries.stream()
          .filter(entry -> entry.getName().startsWith("BOOT-INF/lib/devpath-shared-"))
          .toList();
      if (sharedEntries.size() != 1 || !nestedName.equals(sharedEntries.get(0).getName())) {
        throw new IllegalArgumentException(
            "release bootJar does not contain exactly the immutable Shared artifact");
      }
      byte[] nested = bootJar.getInputStream(sharedEntries.get(0)).readAllBytes();
      if (!sharedHash.equals(sha256(nested))) {
        throw new IllegalArgumentException(
            "release bootJar Shared artifact differs from evaluation input");
      }
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("release bootJar is missing or invalid", failure);
    }
    return new ReleaseArtifacts(
        coordinate, sharedHash, sha256(inputs.bootJar()), graphHash, bootGraphHash);
  }

  private static List<Model> runtimeModels(Path renderedPath, Path applicationPath,
      Map<String, String> evaluationEndpoints) {
    String rendered = readText(renderedPath, "rendered config");
    String application = readText(applicationPath, "application config");
    Map<String, String> runtime = renderedEnvironment(rendered);
    Set<String> runtimeIdentities = renderedEnvironmentNames(rendered);
    Map<String, String> defaults = applicationDefaults(application);
    String primary = required(runtime, "MENTOR_PROVIDER");
    String fallbackRaw = required(runtime, "MENTOR_FALLBACK");
    List<String> fallbacks = java.util.Arrays.stream(fallbackRaw.split(","))
        .map(String::trim).filter(value -> !value.isEmpty()).toList();
    if (fallbacks.size() != 1) {
      throw new IllegalArgumentException("release config must name exactly one Mentor fallback");
    }
    List<Model> result = new ArrayList<>();
    result.add(model("primary", primary, runtime, runtimeIdentities, defaults,
        evaluationEndpoints));
    result.add(model("fallback", fallbacks.get(0), runtime, runtimeIdentities, defaults,
        evaluationEndpoints));
    if (result.get(0).provider().equals(result.get(1).provider())) {
      throw new IllegalArgumentException("primary and fallback providers must be distinct");
    }
    return List.copyOf(result);
  }

  private static Model model(String role, String provider, Map<String, String> runtime,
      Set<String> runtimeIdentities, Map<String, String> defaults,
      Map<String, String> evaluationEndpoints) {
    String modelId;
    String endpoint;
    String evaluationEndpoint;
    String credentialEnv;
    switch (provider) {
      case "ollama" -> {
        modelId = configured(runtime, defaults, "MENTOR_OLLAMA_MODEL");
        endpoint = configured(runtime, defaults, "OLLAMA_BASE_URL");
        evaluationEndpoint = required(evaluationEndpoints, "ollama");
        credentialEnv = null;
      }
      case "claude" -> {
        modelId = configured(runtime, defaults, "MENTOR_CLAUDE_MODEL");
        endpoint = configured(runtime, defaults, "MENTOR_CLAUDE_BASE_URL");
        evaluationEndpoint = evaluationEndpoints.getOrDefault("claude", endpoint);
        credentialEnv = "ANTHROPIC_API_KEY";
        if (!runtimeIdentities.contains(credentialEnv)) {
          throw new IllegalArgumentException(
              "release fallback credential identity is absent from runtime config");
        }
      }
      default -> throw new IllegalArgumentException("unsupported Mentor release provider");
    }
    validateEndpoint(endpoint);
    validateEndpoint(evaluationEndpoint);
    return new Model(role, provider, modelId, endpoint, evaluationEndpoint, credentialEnv);
  }

  private static Map<String, String> renderedEnvironment(String yaml) {
    Map<String, String> values = new LinkedHashMap<>();
    String pending = null;
    for (String line : yaml.lines().toList()) {
      Matcher name = ENV_NAME.matcher(line);
      if (name.matches()) {
        pending = name.group(1);
        continue;
      }
      if (pending != null) {
        Matcher value = ENV_VALUE.matcher(line);
        if (value.matches()) {
          values.put(pending, value.group(1).trim());
          pending = null;
        } else if (line.stripLeading().startsWith("- name:")) {
          pending = null;
        }
      }
    }
    return values;
  }

  private static Set<String> renderedEnvironmentNames(String yaml) {
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
    for (String line : yaml.lines().toList()) {
      Matcher name = ENV_NAME.matcher(line);
      if (name.matches()) {
        names.add(name.group(1));
      }
    }
    return Set.copyOf(names);
  }

  private static Map<String, String> applicationDefaults(String yaml) {
    Map<String, String> defaults = new LinkedHashMap<>();
    Matcher matcher = ENV_DEFAULT.matcher(yaml);
    while (matcher.find()) {
      defaults.put(matcher.group(1), matcher.group(2).trim());
    }
    return defaults;
  }

  private static String configured(Map<String, String> runtime, Map<String, String> defaults,
      String name) {
    String value = runtime.getOrDefault(name, defaults.get(name));
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be bound by release config");
    }
    return value;
  }

  private static String required(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required in rendered release config");
    }
    return value;
  }

  private static String readText(Path path, String label) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (Exception failure) {
      throw new IllegalArgumentException(label + " is missing", failure);
    }
  }

  private static void validateEndpoint(String value) {
    try {
      URI uri = URI.create(value);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("release provider endpoint is invalid");
      }
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("release provider endpoint is invalid");
    }
  }

  record Model(String role, String provider, String modelId, String runtimeEndpoint,
               String evaluationEndpoint, String credentialEnv) {}

  private record ReleaseArtifacts(String sharedCoordinate, String sharedArtifactSha256,
                                  String bootJarSha256,
                                  String runtimeDependencyGraphSha256,
                                  String bootLibraryGraphSha256) {}

  record Inputs(String releaseId, String sourceRevision, String checkedOutSourceRevision,
                String gitOpsRevision,
                Path renderedConfig, Path applicationConfig, Path fixture,
                List<MentorGoldenCase> cases, MentorPromptBuilder prompts,
                Map<String, String> evaluationEndpoints,
                Path bootJar, Path sharedArtifact, Path dependencyGraph,
                Path currentDependencyGraph, Path bootLibraryGraph, Path gradleProperties) {

    static Inputs fromEnvironment(Map<String, String> environment) {
      String source = requiredEnvironment(environment, "MENTOR_EVAL_SOURCE_REVISION");
      return new Inputs(
          requiredEnvironment(environment, "MENTOR_EVAL_RELEASE_ID"),
          source,
          resolveCheckedOutSourceRevision(),
          requiredEnvironment(environment, "MENTOR_EVAL_GITOPS_REVISION"),
          Path.of(requiredEnvironment(environment, "MENTOR_EVAL_RENDERED_CONFIG")),
          Path.of(environment.getOrDefault(
              "MENTOR_EVAL_APPLICATION_CONFIG", "src/main/resources/application.yml")),
          Path.of(environment.getOrDefault(
              "MENTOR_EVAL_FIXTURE", "src/test/resources/eval/golden-mentor-injection.jsonl")),
          MentorGoldenCase.load(Path.of(environment.getOrDefault(
              "MENTOR_EVAL_FIXTURE", "src/test/resources/eval/golden-mentor-injection.jsonl"))),
          new MentorPromptBuilder(),
          evaluationEndpoints(environment),
          Path.of(requiredEnvironment(environment, "MENTOR_EVAL_BOOT_JAR")),
          Path.of(requiredEnvironment(environment, "MENTOR_EVAL_SHARED_ARTIFACT")),
          Path.of(requiredEnvironment(environment, "MENTOR_EVAL_DEPENDENCY_GRAPH")),
          Path.of(requiredEnvironment(environment, "MENTOR_EVAL_CURRENT_DEPENDENCY_GRAPH")),
          Path.of(requiredEnvironment(environment, "MENTOR_EVAL_BOOT_LIBRARY_GRAPH")),
          Path.of(environment.getOrDefault(
              "MENTOR_EVAL_GRADLE_PROPERTIES", "gradle.properties")));
    }

    void validateIdentifiers() {
      if (releaseId == null || releaseId.isBlank()
          || !REVISION.matcher(sourceRevision == null ? "" : sourceRevision).matches()
          || !REVISION.matcher(
              checkedOutSourceRevision == null ? "" : checkedOutSourceRevision).matches()
          || !REVISION.matcher(gitOpsRevision == null ? "" : gitOpsRevision).matches()) {
        throw new IllegalArgumentException("release/source/GitOps identity is invalid");
      }
      if (!sourceRevision.equals(checkedOutSourceRevision)) {
        throw new IllegalArgumentException(
            "declared source revision does not match checked-out source");
      }
      if (cases == null || cases.isEmpty() || prompts == null
          || evaluationEndpoints == null || evaluationEndpoints.isEmpty()
          || bootJar == null || sharedArtifact == null || dependencyGraph == null
          || currentDependencyGraph == null || bootLibraryGraph == null
          || gradleProperties == null) {
        throw new IllegalArgumentException("release fixture and prompt are required");
      }
    }

    Inputs withCheckedOutSourceRevision(String value) {
      return new Inputs(releaseId, sourceRevision, value, gitOpsRevision, renderedConfig,
          applicationConfig, fixture, cases, prompts, evaluationEndpoints,
          bootJar, sharedArtifact, dependencyGraph, currentDependencyGraph, bootLibraryGraph,
          gradleProperties);
    }

    Inputs withEvaluationEndpoints(Map<String, String> value) {
      return new Inputs(releaseId, sourceRevision, checkedOutSourceRevision, gitOpsRevision,
          renderedConfig, applicationConfig, fixture, cases, prompts, Map.copyOf(value),
          bootJar, sharedArtifact, dependencyGraph, currentDependencyGraph, bootLibraryGraph,
          gradleProperties);
    }

    private static Map<String, String> evaluationEndpoints(Map<String, String> environment) {
      Map<String, String> endpoints = new LinkedHashMap<>();
      endpoints.put("ollama", requiredEnvironment(environment, "MENTOR_EVAL_OLLAMA_BASE_URL"));
      return Map.copyOf(endpoints);
    }

    private static String requiredEnvironment(Map<String, String> environment, String name) {
      String value = environment.get(name);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " is required for release evaluation");
      }
      return value.trim();
    }

    static String resolveCheckedOutSourceRevision() {
      Process process = null;
      try {
        process = new ProcessBuilder("git", "rev-parse", "HEAD")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
          throw new IllegalArgumentException("checked-out source revision is unavailable");
        }
        String revision = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!REVISION.matcher(revision).matches()) {
          throw new IllegalArgumentException("checked-out source revision is invalid");
        }
        return revision;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalArgumentException("checked-out source revision is unavailable");
      } catch (java.io.IOException failure) {
        throw new IllegalArgumentException("checked-out source revision is unavailable");
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
        }
      }
    }
  }
}
