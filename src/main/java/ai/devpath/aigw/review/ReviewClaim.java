package ai.devpath.aigw.review;

import java.util.UUID;

/** A fenced lease for one review provider effect. */
public record ReviewClaim(
    long reviewId,
    long sandboxSessionId,
    UUID eventId,
    UUID processingToken) {}
