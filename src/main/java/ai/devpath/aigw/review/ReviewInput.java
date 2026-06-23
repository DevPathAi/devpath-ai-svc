package ai.devpath.aigw.review;

public record ReviewInput(String language, String code, String stdout, String stderr, Integer exitCode) {}
