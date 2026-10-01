package ai.devpath.aigw.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * Claude 가 다시 쓸 수 있는지 확인하는 최소 호출. 출력 1토큰만 요청한다 — 크레딧이 소진된 상태라면
 * 즉시 429 로 떨어져 비용이 아예 없고, 회수된 키라면 401 로 떨어진다.
 *
 * <p>예외를 <b>감싸지 않는다</b>: {@link ProviderProbeScheduler} 가 {@link ProviderFailures} 로
 * 분류해 래치 기한을 늘려야 한다. 감싸면 상태코드가 사라진다.
 */
public class ClaudeProviderProbe implements ProviderProbe {

  private final String feature;
  private final AnthropicClient client;
  private final String model;

  public ClaudeProviderProbe(String feature, AnthropicClient client, String model) {
    this.feature = feature;
    this.client = client;
    this.model = model;
  }

  @Override
  public String feature() { return feature; }

  @Override
  public String provider() { return "claude"; }

  @Override
  public void ping() {
    client.messages().create(MessageCreateParams.builder()
        .model(model)
        .maxTokens(1L)
        .addUserMessage("ping")
        .build());
  }
}
