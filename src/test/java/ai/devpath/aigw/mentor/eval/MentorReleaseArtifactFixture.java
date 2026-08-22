package ai.devpath.aigw.mentor.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class MentorReleaseArtifactFixture {

  static final String VERSION = "0.0.1-et9.20260816";
  static final String COORDINATE = "ai.devpath:devpath-shared:" + VERSION;

  private MentorReleaseArtifactFixture() {}

  static Artifacts create(Path directory) {
    try {
      Files.createDirectories(directory);
      Path shared = directory.resolve("devpath-shared-" + VERSION + ".jar");
      Files.writeString(shared, "synthetic immutable shared artifact");
      String sharedHash = MentorReleaseEvalManifest.sha256(shared);
      Path graph = directory.resolve("runtime-dependency-graph.txt");
      Path resolvedOnly = directory.resolve("resolved-only-runtime.jar");
      Files.writeString(resolvedOnly, "resolved runtime artifact not packaged in bootJar");
      Files.writeString(graph,
          COORDINATE + "|" + shared.getFileName() + "|" + sharedHash + "\n"
              + "test.synthetic:resolved-only:1|" + resolvedOnly.getFileName() + "|"
              + MentorReleaseEvalManifest.sha256(resolvedOnly) + "\n");
      Path currentGraph = directory.resolve("current-runtime-dependency-graph.txt");
      Files.copy(graph, currentGraph, StandardCopyOption.REPLACE_EXISTING);
      Path bootGraph = directory.resolve("boot-library-graph.txt");
      Files.writeString(bootGraph, shared.getFileName() + "|" + sharedHash + "\n");
      Path properties = directory.resolve("gradle.properties");
      Files.writeString(properties, "devpathSharedVersion=" + VERSION + "\n");
      Path bootJar = directory.resolve("devpath-ai-svc-0.0.1-SNAPSHOT.jar");
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bootJar))) {
        zip.putNextEntry(new ZipEntry("BOOT-INF/lib/" + shared.getFileName()));
        zip.write(Files.readAllBytes(shared));
        zip.closeEntry();
      }
      return new Artifacts(bootJar, shared, graph, currentGraph, bootGraph, properties);
    } catch (IOException failure) {
      throw new IllegalStateException("could not create release artifact fixture", failure);
    }
  }

  static void addToEnvironment(Map<String, String> environment, Path directory) {
    Artifacts artifacts = create(directory);
    environment.put("MENTOR_EVAL_BOOT_JAR", artifacts.bootJar().toString());
    environment.put("MENTOR_EVAL_SHARED_ARTIFACT", artifacts.sharedArtifact().toString());
    environment.put("MENTOR_EVAL_DEPENDENCY_GRAPH", artifacts.dependencyGraph().toString());
    environment.put("MENTOR_EVAL_CURRENT_DEPENDENCY_GRAPH",
        artifacts.currentDependencyGraph().toString());
    environment.put("MENTOR_EVAL_BOOT_LIBRARY_GRAPH",
        artifacts.bootLibraryGraph().toString());
    environment.put("MENTOR_EVAL_GRADLE_PROPERTIES", artifacts.gradleProperties().toString());
  }

  record Artifacts(Path bootJar, Path sharedArtifact, Path dependencyGraph,
                   Path currentDependencyGraph, Path bootLibraryGraph,
                   Path gradleProperties) {}
}
