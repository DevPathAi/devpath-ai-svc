package ai.devpath.aigw.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.aigw.community.CommunitySeedClaudeConfig;
import ai.devpath.aigw.retention.RetentionClaudeClientConfig;
import ai.devpath.aigw.review.ClaudeClientConfig;
import com.anthropic.client.AnthropicClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

  @Test
  void disablesSdkInternalRetriesSoTheLatchSeesTheRealFailure() {
    // SDK 기본 재시도는 429 를 삼켜 ProviderLatch 의 rate_limit 판정을 왜곡한다(스펙 보정 §D-②).
    for (String relative : new String[] {
        "review/ClaudeClientConfig.java",
        "community/CommunitySeedClaudeConfig.java",
        "retention/RetentionClaudeClientConfig.java"}) {
      String source = sourceOf(relative);
      assertThat(source).as(relative).contains("maxRetries(0)");
      assertThat(source).as(relative).doesNotContain("fromEnv()");
      assertThat(source).as(relative).contains("ConditionalOnExpression");
      assertThat(source).as(relative).doesNotContain("ConditionalOnProperty");
    }
  }

  private static String sourceOf(String relative) {
    try {
      return Files.readString(Path.of("src/main/java/ai/devpath/aigw", relative));
    } catch (IOException e) {
      throw new AssertionError("설정 소스를 읽을 수 없다: " + relative, e);
    }
  }
}
