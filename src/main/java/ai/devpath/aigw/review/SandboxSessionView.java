package ai.devpath.aigw.review;

public record SandboxSessionView(
    Long id, Long userId, String language, Long contentId,
    String submittedCode, String stdout, String stderr, Integer exitCode, String status) {}
