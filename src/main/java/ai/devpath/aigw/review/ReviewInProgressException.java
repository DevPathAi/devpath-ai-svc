package ai.devpath.aigw.review;

/** Keeps a Kafka record unacknowledged until the active lease completes or expires. */
final class ReviewInProgressException extends RuntimeException {
  ReviewInProgressException(long sandboxSessionId) {
    super("review lease is still active for session " + sandboxSessionId);
  }
}
