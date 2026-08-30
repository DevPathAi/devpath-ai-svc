package ai.devpath.aigw.mentor.eval;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** CI entry point for generating the immutable manifest and validating the resulting evidence. */
public final class MentorReleaseEvalManifestCli {

  private MentorReleaseEvalManifestCli() {}

  public static void main(String[] args) {
    if (args.length != 1) {
      throw new IllegalArgumentException("expected generate-manifest or verify-evidence");
    }
    Map<String, String> environment = System.getenv();
    MentorReleaseEvalManifest.Inputs inputs =
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment);
    Path manifestPath = Path.of(required(environment, "MENTOR_EVAL_MANIFEST"));
    switch (args[0]) {
      case "generate-manifest" -> {
        MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.create(inputs);
        manifest.write(manifestPath);
        manifest.validate(inputs);
        System.out.printf(
            "[mentor-eval-manifest] release=%s source=%s gitops=%s config=%s shared=%s "
                + "boot=%s runtimeGraph=%s bootGraph=%s prompt=%s fixture=%s%n",
            manifest.releaseId(), manifest.sourceRevision(), manifest.gitOpsRevision(),
            manifest.renderedConfigSha256(), manifest.sharedArtifactSha256(),
            manifest.bootJarSha256(), manifest.runtimeDependencyGraphSha256(),
            manifest.bootLibraryGraphSha256(), manifest.promptSha256(),
            manifest.fixtureSha256());
      }
      case "verify-evidence" -> {
        MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.read(manifestPath);
        manifest.validate(inputs);
        manifest.validateNoRemoteCredentials();
        MentorEvalEvidence evidence = MentorEvalEvidence.read(
            Path.of(required(environment, "MENTOR_EVAL_EVIDENCE")));
        evidence.validate(manifest, MentorReleaseEvalManifest.sha256(manifestPath),
            Clock.systemUTC());
        System.out.printf(
            "[mentor-eval-evidence] release=%s source=%s manifest=%s result=PASS%n",
            manifest.releaseId(), manifest.sourceRevision(),
            MentorReleaseEvalManifest.sha256(manifestPath));
      }
      default -> throw new IllegalArgumentException("unknown release eval command");
    }
  }

  private static String required(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required for release evaluation");
    }
    return value;
  }
}
