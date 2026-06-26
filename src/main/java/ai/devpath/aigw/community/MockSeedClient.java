package ai.devpath.aigw.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 외부 LLM 없는 결정적 시드 답변(CI/test/미설정 dev 기본). 방향 제시 수준 고정 초안. */
@Component
@ConditionalOnProperty(name = "devpath.community-seed.provider", havingValue = "mock", matchIfMissing = true)
public class MockSeedClient implements AiSeedClient {

  @Override
  public SeedAnswer generate(SeedInput input) {
    return new SeedAnswer(
        "이 질문에 대한 방향을 제시하는 초안입니다. 핵심 개념을 먼저 정리하고, 공식 문서와 예제를 확인해 보세요. "
            + "더 정확한 답변은 다른 학습자/멘토의 답변을 참고하시기 바랍니다.");
  }

  @Override
  public String providerName() { return "MOCK"; }
}
