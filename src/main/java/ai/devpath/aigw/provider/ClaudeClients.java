package ai.devpath.aigw.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;

/**
 * review·community-seed·retention 세 기능의 Claude 클라이언트를 같은 규칙으로 만든다.
 * 멘토는 자기 빌더({@code MentorClaudeClientConfig.buildClient})를 그대로 쓴다 —
 * 스펙 §4 의 「멘토 무변경」 제약.
 */
public final class ClaudeClients {

  /**
   * SDK 기본 재시도 횟수. 실측(anthropic-java-core 2.34.0): {@code ClientOptions$Builder} 의
   * 생성자가 {@code maxRetries = 2} 를 넣고, 기본 timeout 은 connect 1분 · read/write/request 10분이다.
   */
  private static final int SDK_DEFAULT_MAX_RETRIES = 2;

  private ClaudeClients() {}

  public static AnthropicClient build(
      String apiKey, String baseUrl, Duration timeout, String provider, String fallbackCsv) {
    return AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .timeout(timeout)
        .maxRetries(maxRetriesFor(provider, fallbackCsv))
        .build();
  }

  /**
   * SDK 재시도 예산은 <b>체인을 따라간다</b>.
   *
   * <p>보정 §D-② 가 재시도를 끈 이유는 「SDK 가 429 를 삼키면 {@link ProviderLatch} 의 rate_limit
   * 판정이 왜곡된다」이고, 그 왜곡은 <b>체인이 있을 때만</b> 생긴다. 보정 §C 는 {@code *_FALLBACK} 을
   * 빈 값으로 출하하므로 지금 운영의 체인은 전부 길이 1 이고 래퍼도 래치도 끼지 않는다
   * ({@code ReviewClientConfig} 가 맨 클라이언트를 그대로 돌려준다). 그 상태에서 재시도를 끄는 것은
   * 이득 없는 순손실이다 — 한 번의 503·429 가 그대로 올라온다. 특히:
   *
   * <ul>
   *   <li><b>community-seed</b> 는 {@code CommunitySeedService} 가 예외를 잡아 {@code publishFailed}
   *       로 끝낸다 — Kafka 재시도가 없어서 한 번의 503 이 그 질문의 시드 답변을 영구히 잃는다.</li>
   *   <li><b>retention</b> 은 {@code ReEngagementController} 의 동기 HTTP 라 재시도가 아예 없다.</li>
   *   <li>review 만 {@code TransientReviewException → releaseForRetry} 로 Kafka 재시도·리스 회수가
   *       받아 준다.</li>
   * </ul>
   *
   * <p>그래서 체인을 운영할 의도가 보일 때(지정된 provider 가 둘 이상)만 0 으로 내린다. 타임아웃은
   * 체인과 무관하게 60초로 둔다 — SDK 기본 10분은 결정된 값이 아니라 기본값이고, 그 사이 Kafka 리스와
   * 동기 HTTP 요청이 10분간 붙들린다. 멘토가 이미 같은 자리에서 50초를 쓴다.
   */
  static int maxRetriesFor(String provider, String fallbackCsv) {
    return ProviderChain.requestedCount(provider, fallbackCsv) >= 2 ? 0 : SDK_DEFAULT_MAX_RETRIES;
  }
}
