package ai.devpath.aigw.community;

/** AI 시드 답변 결과(자유 텍스트 마크다운). provider는 AiSeedClient.providerName()로 노출. */
public record SeedAnswer(String content) {}
