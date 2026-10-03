package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

class OllamaHttpTest {

  @Test
  void keepsTheConnectAndReadTimeoutsApart() {
    // Spring 7.0.8 SimpleClientHttpRequestFactory 는 두 값을 private int(ms)로 든다(javap 실측).
    SimpleClientHttpRequestFactory factory =
        OllamaHttp.requestFactory(Duration.ofSeconds(3), Duration.ofSeconds(60));

    assertEquals(3_000, ReflectionTestUtils.getField(factory, "connectTimeout"));
    assertEquals(60_000, ReflectionTestUtils.getField(factory, "readTimeout"));
  }
}
