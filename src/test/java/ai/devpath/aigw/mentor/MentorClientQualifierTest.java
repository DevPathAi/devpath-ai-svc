package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.anthropic.client.AnthropicClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

class MentorClientQualifierTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(MentorClientConfig.class, MultipleAnthropicClients.class)
      .withPropertyValues(
          "devpath.mentor.provider=ollama",
          "devpath.mentor.fallback=claude",
          "devpath.mentor.ollama-model=qwen2.5:3b",
          "devpath.mentor.claude-model=claude-sonnet-4-6",
          "devpath.ollama.base-url=http://localhost:11434");

  @Test
  void selectsOnlyTheMentorAnthropicClientWhenOtherFeaturesAlsoUseClaude() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(AiMentorClient.class);
      assertThat(context.getBean(AiMentorClient.class))
          .isInstanceOf(FallbackMentorClient.class);
    });
  }

  @Configuration(proxyBeanMethods = false)
  static class MultipleAnthropicClients {

    @Bean
    MentorTimeoutPolicy mentorTimeoutPolicy() {
      return new MentorTimeoutPolicy(
          Duration.ofSeconds(50), Duration.ofSeconds(55), Duration.ofSeconds(60));
    }

    @Bean
    MentorPromptBuilder mentorPromptBuilder() {
      return new MentorPromptBuilder();
    }

    @Bean
    JsonMapper jsonMapper() {
      return JsonMapper.builder().build();
    }

    @Bean(name = "anthropicClient")
    AnthropicClient reviewAnthropicClient() {
      return mock(AnthropicClient.class);
    }

    @Bean(name = "communitySeedAnthropicClient")
    AnthropicClient communityAnthropicClient() {
      return mock(AnthropicClient.class);
    }

    @Bean(name = "retentionAnthropicClient")
    AnthropicClient retentionAnthropicClient() {
      return mock(AnthropicClient.class);
    }

    @Bean(name = "mentorAnthropicClient")
    AnthropicClient mentorAnthropicClient() {
      return mock(AnthropicClient.class);
    }
  }
}
