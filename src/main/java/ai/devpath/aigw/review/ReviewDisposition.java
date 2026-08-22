package ai.devpath.aigw.review;

/** Outcome used by the Kafka boundary to distinguish safe ACKs from a live/crashed lease. */
public enum ReviewDisposition {
  COMPLETED,
  TERMINAL_DUPLICATE,
  IN_PROGRESS,
  /** The event is not bound to the same session, owner, and original provider effect. */
  REJECTED
}
