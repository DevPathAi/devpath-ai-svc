package ai.devpath.aigw.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.aigw.community.CommunitySeedClaudeConfig;
import ai.devpath.aigw.retention.RetentionClaudeClientConfig;
import ai.devpath.aigw.review.ClaudeClientConfig;
import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 스펙 보정 §D-①: 세 기능의 AnthropicClient 빈이 {@code provider == "claude"} 조건이면
 * {@code ollama} 주 + {@code claude} 상향이 원리적으로 불가능하다. 조건은 <b>키 존재</b>여야 한다.
 */
class ClaudeBeanConditionTest {

  /**
   * ApplicationContextRunner 는 Boot 앱과 달리 ApplicationConversionService 를 등록하지 않아
   * {@code @Value("...:PT60S") Duration} 변환이 실패한다. 실제 앱에는 있는 것을 테스트에만 보충한다.
   */
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(context -> context.getBeanFactory()
          .setConversionService(ApplicationConversionService.getSharedInstance()))
      .withUserConfiguration(
          ClaudeClientConfig.class, CommunitySeedClaudeConfig.class,
          RetentionClaudeClientConfig.class);

  @Test
  void createsTheClaudeBeansFromTheKeyAloneRegardlessOfProvider() {
    runner
        .withPropertyValues(
            "ANTHROPIC_API_KEY=sk-test",
            "devpath.review.provider=ollama",
            "devpath.community-seed.provider=ollama",
            "devpath.retention.provider=ollama")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBeansOfType(AnthropicClient.class)).hasSize(3);
        });
  }

  @Test
  void createsNoClaudeBeanWithoutTheKeySoBootStaysSafe() {
    // Review Focus 3: fallback names claude but the key is absent -> no bean, no boot failure.
    runner
        .withPropertyValues(
            "devpath.review.provider=ollama",
            "devpath.review.fallback=claude",
            "devpath.community-seed.provider=ollama",
            "devpath.retention.provider=ollama")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBeansOfType(AnthropicClient.class)).isEmpty();
        });
  }

  // 「maxRetries(0) 가 소스에 있는가」를 Files.readString 으로 보던 검사는 지웠다(리뷰 M2):
  // 주석 안의 문자열도 통과시켰고, 상대경로가 Gradle 작업 디렉터리에 의존했으며, 무엇보다
  // 그 값은 이제 조건부다. 대체는 행동 검증이다 —
  //   재시도 예산: ClaudeRetryBudgetTest(MockWebServer 가 실제 요청 횟수를 센다)
  //   빈 조건:     위의 두 테스트(키만으로 생기고, 키가 없으면 안 생긴다)
}
