package ai.devpath.aigw.provider;

import java.time.Duration;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * 폴백 자리 Ollama 의 생존 탐색기(스펙 2026-10-03 §3.1-4). {@code GET /api/tags} 로 응답하는지,
 * 설정한 모델이 <b>정확히 같은 이름으로</b> 올라와 있는지 본다.
 *
 * <p>생존 탐색({@link ProviderProbeScheduler#runLivenessProbes})과 열린 래치의 복구 탐색
 * ({@link ProviderProbeScheduler#runDueProbes}) 양쪽에 쓰인다. 예외를 감싸지 않는다 —
 * {@link ProviderFailures} 가 상태코드·연결 실패·{@link OllamaModelUnavailableException} 을 분류한다.
 * 연결 3초·읽기 5초 상한으로 단일 스케줄러 스레드를 오래 붙들지 않는다.
 */
public class OllamaProviderProbe implements ProviderProbe {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  private final String feature;
  private final String model;
  private final RestClient restClient;

  public OllamaProviderProbe(String feature, String baseUrl, String model) {
    this(feature, baseUrl, model, CONNECT_TIMEOUT, READ_TIMEOUT);
  }

  OllamaProviderProbe(
      String feature, String baseUrl, String model, Duration connectTimeout, Duration readTimeout) {
    this.feature = feature;
    this.model = model;
    this.restClient = RestClient.builder().baseUrl(baseUrl)
        .requestFactory(OllamaHttp.requestFactory(connectTimeout, readTimeout)).build();
  }

  @Override
  public String feature() { return feature; }

  @Override
  public String provider() { return "ollama"; }

  @Override
  public boolean livenessTarget() { return true; }

  @Override
  public void ping() {
    Tags tags = restClient.get().uri("/api/tags").retrieve().body(Tags.class);
    boolean loaded = tags != null && tags.models() != null
        && tags.models().stream().anyMatch(m -> model.equals(m.name()));
    if (!loaded) {
      throw new OllamaModelUnavailableException("Ollama model is not loaded: " + model);
    }
  }

  private record Tags(List<Model> models) {}

  private record Model(String name) {}
}
