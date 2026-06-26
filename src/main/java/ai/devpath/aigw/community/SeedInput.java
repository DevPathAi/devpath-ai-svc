package ai.devpath.aigw.community;

/** AI 시드 답변 생성 입력(질문 제목·본문). 신뢰불가 데이터 — SeedPromptBuilder가 델리미터 격리. */
public record SeedInput(String title, String bodyMd) {}
