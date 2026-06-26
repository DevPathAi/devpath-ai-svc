package ai.devpath.aigw.community;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CI/dev 기본 시드 클라이언트(외부 LLM 없음). */
class MockSeedClientTest {

  private final MockSeedClient client = new MockSeedClient();

  @Test
  void generatesNonEmptyDraftAnswer() {
    SeedAnswer answer = client.generate(new SeedInput("비동기란?", "Future와 async/await가 헷갈립니다."));
    assertThat(answer).isNotNull();
    assertThat(answer.content()).isNotBlank();
    assertThat(client.providerName()).isEqualTo("MOCK");
  }
}
