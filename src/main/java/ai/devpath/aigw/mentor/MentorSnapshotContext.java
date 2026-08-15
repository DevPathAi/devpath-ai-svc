package ai.devpath.aigw.mentor;

/**
 * LCS가 owner/purpose/visibility를 승인한 뒤 반환한 불변 Mentor context.
 *
 * <p>{@code envelopeJson}은 영속할 전체 소비 envelope이고, {@code providerContextJson}은 그
 * envelope의 {@code fieldsIncluded}/{@code content}를 그대로 담은 provider 경계 payload다.
 */
public record MentorSnapshotContext(
    long snapshotId,
    String envelopeJson,
    String providerContextJson) {

  public MentorSnapshotContext {
    if (snapshotId <= 0 || envelopeJson == null || envelopeJson.isBlank()
        || providerContextJson == null || providerContextJson.isBlank()) {
      throw new IllegalArgumentException("invalid mentor snapshot context");
    }
  }
}
