package ai.devpath.aigw.review.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.aigw.review.AiReviewClient;
import ai.devpath.aigw.review.OllamaAiReviewClient;
import ai.devpath.aigw.review.ReviewInput;
import ai.devpath.aigw.review.ReviewPromptBuilder;
import ai.devpath.aigw.review.ReviewResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 골든 품질 게이트. CI 제외(@Tag("eval") — build.gradle.kts가 groups 미지정 시 eval 태그 배제).
 * 로컬/수동 실행:
 *   ollama:  .\gradlew.bat test -Dgroups=eval   (devpath-ollama 가동 + qwen2.5-coder pull)
 *   claude:  ANTHROPIC_API_KEY=... 환경에서 ClaudeAiReviewClient로 교체해 실행
 *
 * 케이스(현재 시드 5건)는 src/test/resources/eval/golden-reviews.jsonl. 50건 확장 + 검출율 바(0.7)
 * 튜닝은 실모델 가동 시 수행한다(system prompt가 메시지를 한국어로 출력하므로 expectKeyword는 코드
 * 식별자/에러명 등 언어중립 토큰을 권장; 미세조정 필요).
 */
@Tag("eval")
class GoldenReviewEvalTest {

  @Test
  void allGoldenCasesProduceValidSchemaAndDetectExpectedIssue() {
    AiReviewClient client = new OllamaAiReviewClient(
        System.getProperty("ollama.base-url", "http://localhost:11434"),
        System.getProperty("review.ollama-model", "qwen2.5-coder:7b"),
        Duration.ofSeconds(120), new ReviewPromptBuilder(), JsonMapper.builder().build());

    List<GoldenCase> cases = GoldenCase.load("/eval/golden-reviews.jsonl");
    assertThat(cases).isNotEmpty();

    int passed = 0;
    for (GoldenCase c : cases) {
      ReviewResult r = client.review(
          new ReviewInput(c.language(), c.code(), c.stdout(), c.stderr(), c.exitCode()));
      // 1) 스키마 유효(파싱 성공) + confidence 범위
      assertThat(r.confidence()).isBetween(0, 100);
      // 2) 기대 이슈 검출(인젝션 케이스는 비스키마 오염 없음으로 통과)
      if (c.detects(r)) {
        passed++;
      }
    }
    double rate = (double) passed / cases.size();
    assertThat(rate).as("골든 검출율").isGreaterThanOrEqualTo(0.7); // 품질 바(조정 가능)
  }
}
