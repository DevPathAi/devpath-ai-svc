package ai.devpath.aigw.review;

import java.util.List;

public record ReviewResult(
    int confidence, List<String> strengths, List<ReviewIssue> improvements, List<ReviewIssue> security) {}
