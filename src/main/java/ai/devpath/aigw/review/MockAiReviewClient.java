package ai.devpath.aigw.review;

import java.util.List;

/** 외부 LLM 없는 결정적 리뷰(CI/test/미설정 dev 기본). 실행결과(exitCode)에 그라운딩. */
public class MockAiReviewClient implements AiReviewClient {

  @Override
  public ReviewResult review(ReviewInput input) {
    boolean failed = input.exitCode() != null && input.exitCode() != 0;
    if (failed) {
      return new ReviewResult(
          40,
          List.of("코드를 수신했습니다 (" + input.language() + ")"),
          List.of(new ReviewIssue("실행이 비정상 종료되었습니다(exit=" + input.exitCode() + ")", null, "warning")),
          List.of());
    }
    return new ReviewResult(
        85,
        List.of("정상 실행되었습니다 (" + input.language() + ")"),
        List.of(),
        List.of());
  }

  @Override
  public String providerName() { return "MOCK"; }
}
