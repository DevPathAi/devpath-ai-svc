package ai.devpath.aigw.review;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class SandboxClient {

  private final RestClient restClient;

  public SandboxClient(
      @Value("${devpath.sandbox.base-url:http://localhost:8085}") String baseUrl,
      @Value("${devpath.sandbox.timeout:PT5S}") Duration timeout) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(timeout);
    requestFactory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  public SandboxSessionView getSession(long sandboxSessionId) {
    try {
      SandboxSessionView view = restClient.get()
          .uri("/internal/sandbox/sessions/{id}", sandboxSessionId)
          .retrieve()
          .body(SandboxSessionView.class);
      if (view == null || view.submittedCode() == null) {
        throw new SandboxUnavailableException(
            "sandbox session response is incomplete: " + sandboxSessionId, null);
      }
      return view;
    } catch (RestClientException e) {
      throw new SandboxUnavailableException("sandbox session fetch failed: " + sandboxSessionId, e);
    }
  }

  /** 사용자별 최근 N개 실행(빌드 B). 멘토 context_snapshot용. 실패는 빈 리스트(맥락 결손 허용). */
  public List<SandboxSessionView> recentByUser(long userId, int limit) {
    try {
      SandboxSessionView[] arr = restClient.get()
          .uri(uriBuilder -> uriBuilder.path("/internal/sandbox/sessions/recent")
              .queryParam("userId", userId)
              .queryParam("limit", limit)
              .build())
          .retrieve()
          .body(SandboxSessionView[].class);
      return arr == null ? List.of() : List.of(arr);
    } catch (RestClientException e) {
      return List.of();
    }
  }
}
