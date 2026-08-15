package ai.devpath.aigw.mentor.eval;

import ai.devpath.aigw.mentor.MentorInput;
import ai.devpath.aigw.mentor.MentorPromptBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic proof that every sensitive ON field is absent from its following OFF request. */
final class MentorProviderPayloadContract {

  private MentorProviderPayloadContract() {}

  static List<Result> validate(List<MentorGoldenCase> cases, MentorPromptBuilder prompts) {
    Map<String, List<MentorGoldenCase>> sequences = new LinkedHashMap<>();
    for (MentorGoldenCase goldenCase : cases) {
      if (goldenCase.sequenceId() != null) {
        sequences.computeIfAbsent(goldenCase.sequenceId(), ignored -> new ArrayList<>())
            .add(goldenCase);
      }
    }
    if (sequences.isEmpty()) {
      throw new IllegalArgumentException("paired provider-payload cases are required");
    }

    List<Result> results = new ArrayList<>();
    sequences.forEach((sequenceId, sequence) -> {
      sequence.sort(Comparator.comparing(MentorGoldenCase::sequenceStep));
      if (sequence.size() != 2
          || !Integer.valueOf(1).equals(sequence.get(0).sequenceStep())
          || !Integer.valueOf(2).equals(sequence.get(1).sequenceStep())) {
        throw new IllegalArgumentException("payload sequence must contain ordered ON then OFF");
      }
      MentorGoldenCase on = sequence.get(0);
      MentorGoldenCase off = sequence.get(1);
      String sentinel = requireSentinel(on);
      if (!sentinel.equals(requireSentinel(off))
          || !Boolean.TRUE.equals(on.payloadMustContain())
          || !Boolean.FALSE.equals(off.payloadMustContain())) {
        throw new IllegalArgumentException("payload sequence policy is invalid");
      }
      if (on.question().contains(sentinel) || off.question().contains(sentinel)) {
        throw new IllegalArgumentException("payload sentinel must originate only from context");
      }
      if (on.context() == null || !on.context().contains(sentinel)
          || (off.context() != null && off.context().contains(sentinel))) {
        throw new IllegalArgumentException("ON/OFF fixture does not prove field removal");
      }
      String onPayload = payload(prompts, on);
      String offPayload = payload(prompts, off);
      boolean onContains = onPayload.contains(sentinel);
      boolean offContains = offPayload.contains(sentinel);
      if (!onContains || offContains) {
        throw new IllegalArgumentException("provider payload violates ON/OFF field policy");
      }
      results.add(new Result(sequenceId, sha256(onPayload), sha256(offPayload),
          onContains, offContains));
    });
    return List.copyOf(results);
  }

  private static String payload(MentorPromptBuilder prompts, MentorGoldenCase goldenCase) {
    return prompts.userContent(new MentorInput(
        goldenCase.question(), goldenCase.context(), goldenCase.referenceDocs()));
  }

  private static String requireSentinel(MentorGoldenCase goldenCase) {
    if (goldenCase.payloadSentinel() == null || goldenCase.payloadSentinel().isBlank()) {
      throw new IllegalArgumentException("payload sentinel is required");
    }
    return goldenCase.payloadSentinel();
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  record Result(String sequenceId, String onPayloadSha256, String offPayloadSha256,
                boolean onPayloadContainsSentinel, boolean offPayloadContainsSentinel) {}
}
