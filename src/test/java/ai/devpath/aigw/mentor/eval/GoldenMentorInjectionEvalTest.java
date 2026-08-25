package ai.devpath.aigw.mentor.eval;

import ai.devpath.aigw.mentor.AiMentorClient;
import ai.devpath.aigw.mentor.ClaudeMentorClient;
import ai.devpath.aigw.mentor.MentorInput;
import ai.devpath.aigw.mentor.MentorPromptBuilder;
import ai.devpath.aigw.mentor.OllamaMentorClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live release gate. It consumes one hash-bound manifest and emits only safe IDs, hashes, scores,
 * and latency. Independent model/config system properties are intentionally unsupported.
 */
@Tag("eval")
class GoldenMentorInjectionEvalTest {

  @Test
  void evaluatesEveryOrderedReleaseModelAndWritesFreshBoundEvidence() throws Exception {
    Map<String, String> environment = System.getenv();
    MentorReleaseEvalManifest.Inputs inputs =
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment);
    Path manifestPath = Path.of(required(environment, "MENTOR_EVAL_MANIFEST"));
    Path evidencePath = evidencePath(environment);
    Files.deleteIfExists(evidencePath);

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.read(manifestPath);
    manifest.validate(inputs);
    validateCredentials(manifest, environment);
    MentorPromptBuilder prompts = inputs.prompts();
    List<MentorProviderPayloadContract.Result> payloadResults =
        MentorProviderPayloadContract.validate(inputs.cases(), prompts);
    payloadResults.forEach(result -> System.out.printf(
        "[mentor-eval-payload] release=%s sequence=%s onHash=%s offHash=%s score=1%n",
        manifest.releaseId(), result.sequenceId(), result.onPayloadSha256(),
        result.offPayloadSha256()));

    List<MentorEvalEvidence.ModelResult> modelResults = new ArrayList<>();
    for (MentorReleaseEvalManifest.Model model : manifest.models()) {
      modelResults.add(evaluateModel(manifest, model, inputs.cases(), prompts, environment));
    }

    MentorEvalEvidence evidence = MentorEvalEvidence.evaluated(
        manifest, MentorReleaseEvalManifest.sha256(manifestPath), Instant.now(), modelResults);
    evidence.write(evidencePath);
    evidence.validate(manifest, MentorReleaseEvalManifest.sha256(manifestPath), Clock.systemUTC());
  }

  private MentorEvalEvidence.ModelResult evaluateModel(MentorReleaseEvalManifest manifest,
      MentorReleaseEvalManifest.Model model, List<MentorGoldenCase> cases,
      MentorPromptBuilder prompts, Map<String, String> environment) {
    AiMentorClient client = client(model, prompts, environment);
    int hardPassed = 0;
    int hardTotal = 0;
    int qualityPassed = 0;
    int qualityTotal = 0;
    long totalLatencyMs = 0;
    Map<String, int[]> categoryScores = new LinkedHashMap<>();

    for (MentorGoldenCase goldenCase : cases) {
      MentorInput input = new MentorInput(
          goldenCase.question(), goldenCase.context(), goldenCase.referenceDocs());
      String promptHash = MentorReleaseEvalManifest.sha256(
          prompts.systemPrompt() + "\n" + prompts.userContent(input));
      StringBuilder answer = new StringBuilder();
      long started = System.nanoTime();
      boolean passed;
      try {
        client.stream(input, answer::append);
        String output = answer.toString();
        passed = (goldenCase.mustNotContain() == null
            || !output.contains(goldenCase.mustNotContain()))
            && (goldenCase.mustContain() == null
            || output.contains(goldenCase.mustContain()));
      } catch (RuntimeException failure) {
        System.out.printf(
            "[mentor-eval-provider-failure] release=%s role=%s provider=%s model=%s "
                + "case=%s failure=%s%n",
            manifest.releaseId(), model.role(), model.provider(), model.modelId(),
            goldenCase.caseId(), failureClasses(failure));
        passed = false;
      }
      long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      totalLatencyMs += latencyMs;
      int[] category = categoryScores.computeIfAbsent(
          goldenCase.category(), ignored -> new int[2]);
      category[1]++;
      if (passed) category[0]++;
      if (goldenCase.hardInvariant()) {
        hardTotal++;
        if (passed) hardPassed++;
      } else {
        qualityTotal++;
        if (passed) qualityPassed++;
      }
      System.out.printf(
          "[mentor-eval] release=%s role=%s provider=%s model=%s config=%s fixture=%s "
              + "case=%s category=%s promptHash=%s score=%d latencyMs=%d%n",
          manifest.releaseId(), model.role(), model.provider(), model.modelId(),
          manifest.renderedConfigSha256(), manifest.fixtureRevision(), goldenCase.caseId(),
          goldenCase.category(), promptHash, passed ? 1 : 0, latencyMs);
    }

    categoryScores.forEach((category, score) -> System.out.printf(
        "[mentor-eval-category] release=%s role=%s provider=%s model=%s fixture=%s "
            + "category=%s score=%d/%d%n",
        manifest.releaseId(), model.role(), model.provider(), model.modelId(),
        manifest.fixtureRevision(), category, score[0], score[1]));
    double hardRate = hardTotal == 0 ? 0 : (double) hardPassed / hardTotal;
    double qualityRate = qualityTotal == 0 ? 0 : (double) qualityPassed / qualityTotal;
    return new MentorEvalEvidence.ModelResult(
        model.role(), model.provider(), model.modelId(), hardRate, qualityRate, totalLatencyMs);
  }

  private AiMentorClient client(MentorReleaseEvalManifest.Model model,
      MentorPromptBuilder prompts, Map<String, String> environment) {
    Duration timeout = Duration.ofSeconds(120);
    return switch (model.provider()) {
      case "ollama" -> new OllamaMentorClient(
          model.evaluationEndpoint(), model.modelId(), timeout, prompts,
          JsonMapper.builder().build());
      case "claude" -> {
        String credential = required(environment, model.credentialEnv());
        yield new ClaudeMentorClient(
            AnthropicOkHttpClient.builder()
                .apiKey(credential)
                .baseUrl(model.evaluationEndpoint())
                .timeout(timeout)
                .maxRetries(0)
                .build(),
            model.modelId(), prompts);
      }
      default -> throw new IllegalArgumentException("unsupported release eval provider");
    };
  }

  static void validateCredentials(MentorReleaseEvalManifest manifest,
      Map<String, String> environment) {
    manifest.validateCredentials(environment);
  }

  private static Path evidencePath(Map<String, String> environment) {
    Path path = Path.of(required(environment, "MENTOR_EVAL_EVIDENCE"));
    if (!"mentor-release-eval-evidence-v3.json".equals(path.getFileName().toString())) {
      throw new IllegalArgumentException("release eval evidence filename is invalid");
    }
    return path;
  }

  private static String required(Map<String, String> environment, String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("release eval credential identity is missing");
    }
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required for release model evaluation");
    }
    return value.trim();
  }

  static String failureClasses(RuntimeException failure) {
    StringBuilder classes = new StringBuilder();
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (!classes.isEmpty()) {
        classes.append("->");
      }
      classes.append(current.getClass().getSimpleName());
      current = current.getCause();
    }
    return classes.toString();
  }
}
