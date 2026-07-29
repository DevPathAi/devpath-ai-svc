package ai.devpath.aigw.mentor;

import com.anthropic.client.AnthropicClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 멘토 클라이언트 단일 진입점 조립. provider(primary) + devpath.mentor.fallback(순서형 CSV)을
 * 가용 provider로 필터링해 체인을 만든다. 체인 길이 1이면 그 client, 2 이상이면 FallbackMentorClient.
 * claude는 AnthropicClient 빈(키 존재)이 있을 때만 가용.
 */
@Configuration
public class MentorClientConfig {

  @Bean
  public AiMentorClient mentorClient(
      @Value("${devpath.mentor.provider:mock}") String provider,
      @Value("${devpath.mentor.fallback:}") String fallbackCsv,
      @Value("${devpath.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
      @Value("${devpath.mentor.ollama-model:qwen2.5:7b}") String ollamaModel,
      @Value("${devpath.mentor.timeout:PT60S}") Duration timeout,
      @Value("${devpath.mentor.claude-model:claude-sonnet-4-6}") String claudeModel,
      MentorPromptBuilder prompts, JsonMapper jsonMapper,
      ObjectProvider<AnthropicClient> anthropicClientProvider) {

    Map<String, AiMentorClient> available = new LinkedHashMap<>();
    available.put("ollama",
        new OllamaMentorClient(ollamaBaseUrl, ollamaModel, timeout, prompts, jsonMapper));
    available.put("mock", new MockMentorClient());
    AnthropicClient anthropic = anthropicClientProvider.getIfAvailable();
    if (anthropic != null) {
      available.put("claude", new ClaudeMentorClient(anthropic, claudeModel, prompts));
    }

    List<AiMentorClient> chain = orderedChain(provider, fallbackCsv, available);
    if (chain.isEmpty()) {
      chain = List.of(available.get("mock")); // 안전망: 최소 mock
    }
    return chain.size() == 1 ? chain.get(0) : new FallbackMentorClient(chain);
  }

  /** provider+fallback 순서로 available에 존재하는 client만, 중복 제거해 반환. */
  static List<AiMentorClient> orderedChain(String provider, String fallbackCsv,
      Map<String, AiMentorClient> available) {
    List<String> order = new ArrayList<>();
    order.add(provider == null ? "" : provider.trim());
    if (fallbackCsv != null) {
      for (String f : fallbackCsv.split(",")) {
        String t = f.trim();
        if (!t.isEmpty()) order.add(t);
      }
    }
    List<AiMentorClient> chain = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String name : order) {
      if (name.isEmpty() || !seen.add(name)) continue;
      AiMentorClient c = available.get(name);
      if (c != null) chain.add(c);
    }
    return chain;
  }
}
