package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.aigw.mentor.MentorPromptBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MentorReleaseArtifactPipelineTest {

  @TempDir Path temp;

  @Test
  void realBootJarPrepareAndManifestGenerationUseDistinctExactGraphs() throws Exception {
    Path releaseInputs = requiredPath("mentorReleaseInputsDir");
    Path bootJar = requiredPath("mentorBootJar");
    Path currentRuntimeGraph = requiredPath("mentorCurrentRuntimeGraph");
    Path runtimeGraph = releaseInputs.resolve("runtime-dependency-graph.txt");
    Path bootGraph = releaseInputs.resolve("boot-library-graph.txt");
    Path shared = releaseInputs.resolve(
        "devpath-shared-" + MentorReleaseArtifactFixture.VERSION + ".jar");
    List<String> runtimeLines = Files.readAllLines(runtimeGraph);
    List<String> bootLines = Files.readAllLines(bootGraph);

    assertThat(bootJar).isRegularFile();
    assertThat(shared).isRegularFile();
    assertThat(runtimeLines).isSorted().allMatch(line -> line.split("\\|", -1).length == 3);
    assertThat(bootLines).isSorted().allMatch(line -> line.split("\\|", -1).length == 2);
    assertThat(bootLines).hasSizeGreaterThan(10);
    assertThat(Files.mismatch(runtimeGraph, currentRuntimeGraph)).isEqualTo(-1L);
    assertThat(runtimeLines).isNotEqualTo(bootLines);

    String sharedHash = MentorReleaseEvalManifest.sha256(shared);
    assertThat(runtimeLines).contains(MentorReleaseArtifactFixture.COORDINATE + "|"
        + shared.getFileName() + "|" + sharedHash);
    assertThat(bootLines).contains(shared.getFileName() + "|" + sharedHash);

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
    Path fixture = Path.of("src/test/resources/eval/golden-mentor-injection.jsonl");
    String source = MentorReleaseEvalManifest.Inputs.resolveCheckedOutSourceRevision();
    MentorReleaseEvalManifest.Inputs inputs = new MentorReleaseEvalManifest.Inputs(
        "functional-" + source, source, source, "b".repeat(40), rendered,
        Path.of("src/main/resources/application.yml"), fixture,
        MentorGoldenCase.load(fixture), new MentorPromptBuilder(),
        java.util.Map.of("ollama", "https://eval-ollama.example.test"),
        bootJar, shared, runtimeGraph, currentRuntimeGraph, bootGraph,
        Path.of("gradle.properties"));

    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(inputs);
    Path manifestPath = temp.resolve("mentor-release-eval-manifest-v3.json");
    manifest.write(manifestPath);
    MentorReleaseEvalManifest.read(manifestPath).validate(inputs);

    assertThat(manifest.bootJarSha256()).isEqualTo(MentorReleaseEvalManifest.sha256(bootJar));
    assertThat(manifest.runtimeDependencyGraphSha256())
        .isEqualTo(MentorReleaseEvalManifest.sha256(runtimeGraph));
    assertThat(manifest.bootLibraryGraphSha256())
        .isEqualTo(MentorReleaseEvalManifest.sha256(bootGraph));
  }

  private static Path requiredPath(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required by the real release artifact test");
    }
    return Path.of(value);
  }
}
