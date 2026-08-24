package ai.devpath.aigw.release;

/** Safe, synthetic failure raised only after one provider token has been delivered in staging. */
public final class ReleaseInjectedMentorException extends RuntimeException {
  public ReleaseInjectedMentorException() {
    super("release mentor fault");
  }
}
