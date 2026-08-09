package ai.devpath.aigw.mentor;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** learning-svc 지식베이스 검색(게이트웨이 미경유). 실패는 빈 리스트 — 답변은 계속된다. */
@Component
public class KnowledgeClient {

  private final RestClient restClient;

  public KnowledgeClient(
      @Value("${devpath.learning.base-url:http://localhost:8081}") String baseUrl,
      @Value("${devpath.learning.timeout:PT5S}") Duration timeout) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  public List<KnowledgeChunk> searchSimilar(List<Double> embedding, int limit) {
    try {
      KnowledgeChunk[] arr = restClient.post()
          .uri("/internal/knowledge/similar")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new KnowledgeQuery(embedding, limit))
          .retrieve()
          .body(KnowledgeChunk[].class);
      return arr == null ? List.of() : List.of(arr);
    } catch (RestClientException e) {
      return List.of();
    }
  }

  /** 요청 바디. learning-svc의 KnowledgeQuery와 필드가 같아야 한다. */
  public record KnowledgeQuery(List<Double> embedding, Integer limit) {}
}
