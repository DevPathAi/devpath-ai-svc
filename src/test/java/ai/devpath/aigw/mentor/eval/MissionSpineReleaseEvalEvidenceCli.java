package ai.devpath.aigw.mentor.eval;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** CI-only entry point for adapting and revalidating live ET9 evidence. */
public final class MissionSpineReleaseEvalEvidenceCli {

  private MissionSpineReleaseEvalEvidenceCli() {}

  public static void main(String[] args) {
    if (args.length != 1 || !("preflight".equals(args[0])
        || "generate".equals(args[0]) || "validate".equals(args[0]))) {
      throw new IllegalArgumentException("expected preflight, generate, or validate");
    }
    Map<String, String> environment = System.getenv();
    MentorReleaseEvalManifest.Inputs evalInputs =
        MentorReleaseEvalManifest.Inputs.fromEnvironment(environment);
    Path manifestPath = Path.of(required(environment, "MENTOR_EVAL_MANIFEST"));
    Path candidatePath = Path.of(required(environment, "MISSION_SPINE_CANDIDATE"));
    String candidateSha = required(environment, "MISSION_SPINE_CANDIDATE_SPEC_SHA256");
    String aiSourceSha = required(environment, "MISSION_SPINE_AI_SOURCE_SHA");
    String gitopsSourceSha = required(environment, "MISSION_SPINE_GITOPS_SOURCE_SHA");
    if ("preflight".equals(args[0])) {
      MissionSpineReleaseEvalEvidence.validateCandidate(
          candidatePath, candidateSha, aiSourceSha, gitopsSourceSha,
          evalInputs, manifestPath);
      System.out.printf(
          "[mission-spine-ai-eval] mode=preflight source=%s gitops=%s status=passed%n",
          aiSourceSha, gitopsSourceSha);
      return;
    }
    Path evaluationPath = Path.of(required(environment, "MENTOR_EVAL_EVIDENCE"));
    Path outputPath = Path.of(required(environment, "MISSION_SPINE_RELEASE_EVIDENCE"));
    if (!"evidence.json".equals(outputPath.getFileName().toString())) {
      throw new IllegalArgumentException("Mission Spine release evidence filename is invalid");
    }
    MentorReleaseEvalManifest manifest = MentorReleaseEvalManifest.read(manifestPath);
    manifest.validate(evalInputs);
    manifest.validateNoRemoteCredentials();
    MissionSpineReleaseEvalEvidence.Context context =
        new MissionSpineReleaseEvalEvidence.Context(
            candidateSha,
            positive(environment, "GITHUB_RUN_ID"),
            positive(environment, "GITHUB_RUN_ATTEMPT"),
            aiSourceSha,
            gitopsSourceSha,
            candidatePath,
            evalInputs,
            manifestPath,
            evaluationPath,
            MissionSpineReleaseEvalEvidence.ProtectedApproval.read(
                Path.of(required(environment, "MISSION_SPINE_APPROVAL"))),
            Clock.systemUTC());
    if ("generate".equals(args[0])) {
      MissionSpineReleaseEvalEvidence.create(context).write(outputPath);
    } else {
      MissionSpineReleaseEvalEvidence.validate(outputPath, context);
    }
    System.out.printf(
        "[mission-spine-ai-eval] mode=%s source=%s gitops=%s status=passed%n",
        args[0], context.aiSourceSha(), context.gitopsSourceSha());
  }

  private static String required(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private static long positive(Map<String, String> environment, String name) {
    String value = required(environment, name);
    try {
      long result = Long.parseLong(value);
      if (result < 1 || !Long.toString(result).equals(value)) {
        throw new IllegalArgumentException(name + " must be a positive integer");
      }
      return result;
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException(name + " must be a positive integer", failure);
    }
  }
}
