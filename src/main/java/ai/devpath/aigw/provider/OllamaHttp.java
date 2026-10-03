package ai.devpath.aigw.provider;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Ollama 호출용 요청 팩토리. 연결과 읽기 타임아웃을 <b>따로</b> 둔다(스펙 2026-10-03 §3.1-6).
 *
 * <p>둘을 같은 값(60초)으로 두면, GPU 노드가 갑자기 사라져 엔드포인트가 아직 남아 있는 동안 요청마다
 * 연결에서 60초를 기다린다. 생성은 분 단위라 읽기는 길어야 하지만 연결은 짧아도 된다.
 */
public final class OllamaHttp {

  private OllamaHttp() {}

  public static SimpleClientHttpRequestFactory requestFactory(
      Duration connectTimeout, Duration readTimeout) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(readTimeout);
    return factory;
  }
}
