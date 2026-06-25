package ai.devpath.aigw.mentor;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 운영 멘토(Anthropic Claude 스트리밍). 키(ANTHROPIC_API_KEY)는 SDK가 환경변수로 읽는다.
 * 자유 텍스트 스트림(구조화 출력 없음) — 인젝션 방어는 MentorPromptBuilder(system+델리미터, M-8).
 */
@Component
@ConditionalOnProperty(name = "devpath.mentor.provider", havingValue = "claude")
public class ClaudeMentorClient implements AiMentorClient {

  private final AnthropicClient client;
  private final String model;
  private final MentorPromptBuilder prompts;

  public ClaudeMentorClient(
      @Qualifier("mentorAnthropicClient") AnthropicClient client,
      @Value("${devpath.mentor.claude-model:claude-sonnet-4-6}") String model,
      MentorPromptBuilder prompts) {
    this.client = client;
    this.model = model;
    this.prompts = prompts;
  }

  @Override
  public void stream(MentorInput input, Consumer<String> tokenSink) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(1500L)
        .system(prompts.systemPrompt())
        .addUserMessage(prompts.userContent(input))
        .build();
    // anthropic-java 2.34.0 blocking 스트리밍: StreamResponse<RawMessageStreamEvent>.
    // content_block_delta 이벤트의 text_delta.text를 tokenSink로 push.
    try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
      stream.stream()
          .flatMap(event -> event.contentBlockDelta().stream())
          .flatMap(deltaEvent -> deltaEvent.delta().text().stream())
          .forEach(textDelta -> tokenSink.accept(textDelta.text()));
    }
  }

  @Override
  public String providerName() { return "CLAUDE"; }
}
