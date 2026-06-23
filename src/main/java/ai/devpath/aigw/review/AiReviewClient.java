package ai.devpath.aigw.review;

/** 코드리뷰 LLM 추상화. 구현: Mock(C1), Ollama·Claude(C2). 동일 출력 스키마. */
public interface AiReviewClient {
  ReviewResult review(ReviewInput input);
  String providerName();
}
