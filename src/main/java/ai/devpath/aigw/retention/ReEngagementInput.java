package ai.devpath.aigw.retention;

import java.time.Instant;

public record ReEngagementInput(long userId, Instant lastActiveAt, int daysInactive, String currentLearningPathSummary) {
}
