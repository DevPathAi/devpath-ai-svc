package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ImmutableImageWorkflowContractTest {

  private static final Path WORKFLOW = Path.of(".github/workflows/ci.yml");
  private static final Pattern PINNED_ACTION = Pattern.compile(
      "^\\s*-?\\s*uses:\\s+[^\\s@]+@[0-9a-f]{40}(?:\\s+#.*)?$");

  @Test
  void defaultCiIsPrSafeAndOnlyProducesAnImmutableMainImage() throws Exception {
    assertContract(Files.readString(WORKFLOW));
  }

  @Test
  void securityRelevantWorkflowMutationsFailTheContract() throws Exception {
    String baseline = normalized(Files.readString(WORKFLOW));
    assertContract(baseline);

    Map<String, String> mutations = new LinkedHashMap<>();
    mutations.put("floating action",
        replaceOnce(baseline,
            "actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803",
            "actions/checkout@v6"));
    mutations.put("cancellable source publication",
        replaceOnce(baseline, "cancel-in-progress: false", "cancel-in-progress: true"));
    mutations.put("mutable image alias",
        replaceOnce(baseline,
            "ghcr.io/devpathai/devpath-ai-svc:${{ github.sha }}",
            "ghcr.io/devpathai/devpath-ai-svc:main"));
    mutations.put("missing OCI revision",
        replaceOnce(baseline,
            "org.opencontainers.image.revision=${{ github.sha }}",
            "org.opencontainers.image.version=${{ github.sha }}"));
    mutations.put("missing OCI source",
        replaceOnce(baseline,
            "org.opencontainers.image.source=https://github.com/${{ github.repository }}",
            "org.opencontainers.image.vendor=DevPathAi"));
    mutations.put("candidate identity reuse bypass",
        replaceOnce(baseline,
            "test \"${RECHECK_CONFIG_DIGEST}\" = \"${CANDIDATE_CONFIG_DIGEST}\"",
            "test \"${RECHECK_CONFIG_DIGEST}\" = \"${PREFLIGHT_CONFIG_DIGEST}\""));
    mutations.put("timestamp rewrite bypass",
        replaceOnce(baseline, "rewrite-timestamp=true", "rewrite-timestamp=false"));
    mutations.put("unrestricted main producer",
        replaceOnce(baseline,
            "github.event_name == 'push' && github.ref == 'refs/heads/main' && github.repository == 'DevPathAi/devpath-ai-svc'",
            "github.ref == 'refs/heads/main'"));
    mutations.put("best effort registry probe",
        replaceOnce(baseline,
            "node .github/scripts/immutable-image-registry.mjs --allow-absent",
            "node .github/scripts/immutable-image-registry.mjs --allow-absent || true"));
    mutations.put("rerun-colliding build artifact",
        baseline.replace(
            "mentor-release-inputs-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}",
            "mentor-release-inputs-${{ github.sha }}"));
    mutations.put("rerun-colliding evidence artifact",
        replaceOnce(baseline,
            "devpath-ai-svc-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}-registry-evidence",
            "devpath-ai-svc-${{ github.sha }}-registry-evidence"));
    mutations.put("direct deployment job", baseline + "\n  deploy:\n    runs-on: ubuntu-latest\n");

    mutations.forEach((name, mutation) -> {
      try {
        assertContract(mutation);
      } catch (AssertionError expected) {
        return;
      }
      throw new AssertionError("contract accepted security mutation: " + name);
    });
  }

  @SuppressWarnings("unchecked")
  private static void assertContract(String input) {
    String workflowText = normalized(input);
    Map<String, Object> workflow = new Yaml().load(workflowText);
    Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");

    assertThat(jobs).containsOnlyKeys("build", "image");
    Map<String, Object> build = (Map<String, Object>) jobs.get("build");
    Map<String, Object> image = (Map<String, Object>) jobs.get("image");
    assertThat(build).isNotNull();
    assertThat(image).isNotNull();
    assertThat(image.get("needs")).isEqualTo("build");
    assertThat(String.valueOf(image.get("if"))).isEqualTo(
        "github.event_name == 'push' && github.ref == 'refs/heads/main' "
            + "&& github.repository == 'DevPathAi/devpath-ai-svc'");

    Map<String, Object> concurrency = (Map<String, Object>) image.get("concurrency");
    assertThat(concurrency.get("group")).isEqualTo("ai-image-${{ github.sha }}");
    assertThat(concurrency.get("cancel-in-progress")).isEqualTo(false);

    String buildText = build.toString();
    String imageText = image.toString();
    assertThat(buildText)
        .contains("prepareMentorReleaseArtifacts")
        .contains("mentor-release-inputs-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}")
        .contains("actions/upload-artifact@")
        .doesNotContain("packages=write", "ANTHROPIC_API_KEY", "MENTOR_EVAL_OLLAMA_BASE_URL");
    assertThat(imageText)
        .contains("actions/download-artifact@")
        .contains("mentor-release-inputs-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}")
        .contains("devpath-ai-svc-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}-registry-evidence")
        .contains("immutable-image-registry.mjs")
        .contains("org.opencontainers.image.revision=${{ github.sha }}")
        .contains("org.opencontainers.image.source=https://github.com/${{ github.repository }}")
        .contains(".rootfs.diff_ids", "CANDIDATE_CONFIG_DIGEST")
        .contains("test \"${RECHECK_CONFIG_DIGEST}\" = \"${CANDIDATE_CONFIG_DIGEST}\"")
        .contains("push=false", "provenance=false")
        .contains("packages=write")
        .doesNotContain("GITOPS_", "ANTHROPIC_API_KEY", "MENTOR_EVAL_OLLAMA_BASE_URL");

    assertThat(count(workflowText,
        "node .github/scripts/immutable-image-registry.mjs --allow-absent"))
        .isEqualTo(2);
    assertThat(count(workflowText,
        "node .github/scripts/immutable-image-registry.mjs --evidence"))
        .isEqualTo(2);
    assertThat(workflowText).doesNotContain(
        "if: steps.immutable-tag.outputs.state == 'absent'");
    assertThat(workflowText)
        .contains("build-args: SOURCE_DATE_EPOCH=1")
        .contains("type=docker,dest=${{ runner.temp }}/devpath-ai-svc-candidate.tar,rewrite-timestamp=true")
        .contains("docker load --input \"${CANDIDATE_ARCHIVE}\"")
        .doesNotContain("rewrite-timestamp=false", "load: true");
    assertThat(workflowText.indexOf("id: tag-recheck"))
        .isLessThan(workflowText.indexOf("docker push \"${TAG_REFERENCE}\""));
    assertThat(workflowText)
        .doesNotContain("release-model-eval", "push-evaluated-gitops", "create-github-app-token")
        .doesNotContain("devpath-gitops", "kustomize edit", "git push")
        .doesNotContain("ghcr.io/devpathai/devpath-ai-svc:main")
        .doesNotContain("ghcr.io/devpathai/devpath-ai-svc:latest")
        .doesNotContain("continue-on-error", "|| true");

    List<String> actionLines = new ArrayList<>();
    workflowText.lines().filter(ImmutableImageWorkflowContractTest::isActionLine)
        .forEach(actionLines::add);
    try (var workflows = Files.list(Path.of(".github/workflows"))) {
      workflows
          .filter(path -> path.toString().endsWith(".yml")
              || path.toString().endsWith(".yaml"))
          .filter(path -> !path.getFileName().toString().equals("ci.yml"))
          .forEach(path -> {
            try {
              Files.readString(path).lines()
                  .filter(ImmutableImageWorkflowContractTest::isActionLine)
                  .forEach(actionLines::add);
            } catch (Exception exception) {
              throw new IllegalStateException(exception);
            }
          });
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
    assertThat(actionLines).isNotEmpty();
    assertThat(actionLines).allMatch(line -> PINNED_ACTION.matcher(line).matches());

    String dockerfile;
    try {
      dockerfile = normalized(Files.readString(Path.of("Dockerfile")));
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
    assertThat(dockerfile).startsWith(
        "FROM eclipse-temurin:21-jre-alpine@sha256:"
            + "3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS runtime\n");
    assertThat(workflowText).contains(
        "pgvector/pgvector:pg17@sha256:"
            + "cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f");
    String dockerignore;
    try {
      dockerignore = normalized(Files.readString(Path.of(".dockerignore")));
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
    assertThat(dockerignore).isEqualTo(
        "**\n!Dockerfile\n!build/\n!build/libs/\n!build/libs/*-SNAPSHOT.jar\n");
  }

  private static String replaceOnce(String input, String needle, String replacement) {
    assertThat(input).contains(needle);
    return input.replaceFirst(Pattern.quote(needle), Matcher.quoteReplacement(replacement));
  }

  private static boolean isActionLine(String line) {
    return line.stripLeading().startsWith("uses:")
        || line.stripLeading().startsWith("- uses:");
  }

  private static int count(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
  }

  private static String normalized(String value) {
    return value.replace("\r\n", "\n");
  }
}
