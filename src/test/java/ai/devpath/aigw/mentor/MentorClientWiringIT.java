package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 배선 실증: provider=ollama + fallback=mock 설정에서 MentorClientConfig 팩토리가
 * 실제로 FallbackMentorClient(체인)로 AiMentorClient 빈을 조립하는지 컨텍스트 로드로 확인.
 * (무키이므로 claude는 체인에서 제외 → 체인=[ollama, mock].)
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "devpath.mentor.provider=ollama",
    "devpath.mentor.fallback=mock"
})
class MentorClientWiringIT {

  @Autowired AiMentorClient mentorClient;

  @Test
  void ollamaWithMockFallback_isWrappedInFallbackClient() {
    assertThat(mentorClient).isInstanceOf(FallbackMentorClient.class);
  }
}
