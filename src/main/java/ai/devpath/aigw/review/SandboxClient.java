package ai.devpath.aigw.review;

import java.time.Duration;
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
}
