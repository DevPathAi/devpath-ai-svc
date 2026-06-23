package ai.devpath.aigw.review;

import java.util.List;

public record CodeReview(
    String id,
    String status,
    int confidence,
    List<String> strengths,
    List<ReviewIssue> improvements,
    List<ReviewIssue> security) {}
