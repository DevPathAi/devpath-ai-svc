package ai.devpath.aigw.mentor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/** learning-svc 내부 조회(게이트웨이 미경유, 빌드 C). 콘텐츠 본문 + 임베딩 유사검색. */
@Component
public class LearningClient {

  private final RestClient restClient;
  private final JsonMapper jsonMapper;

  public LearningClient(
      @Value("${devpath.learning.base-url:http://localhost:8081}") String baseUrl,
      @Value("${devpath.learning.timeout:PT5S}") Duration timeout,
      JsonMapper jsonMapper) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.jsonMapper = jsonMapper;
  }

  /** 현재 콘텐츠 조회. 미존재(404)면 empty(맥락 결손 허용). 그 외 오류도 empty로 폴백(답변은 계속). */
  public Optional<InternalContentView> getContent(long id) {
    try {
      InternalContentView view = restClient.get()
          .uri("/internal/contents/{id}", id)
          .retrieve()
          .onStatus(HttpStatusCode::isError, (req, res) -> { /* swallow → null */ })
          .body(InternalContentView.class);
      return Optional.ofNullable(view);
    } catch (RestClientException e) {
      return Optional.empty();
    }
  }

  /** 질문 임베딩으로 유사 콘텐츠 top-K. 실패는 빈 리스트(references 생략, 토큰 스트림 무관). */
  public List<SimilarContent> searchSimilar(List<Double> embedding, int limit, String track) {
    try {
      SimilarContent[] arr = restClient.post()
          .uri("/internal/contents/similar")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SimilarQuery(embedding, limit, track))
          .retrieve()
          .body(SimilarContent[].class);
      return arr == null ? List.of() : List.of(arr);
    } catch (RestClientException e) {
      return List.of();
    }
  }
}
