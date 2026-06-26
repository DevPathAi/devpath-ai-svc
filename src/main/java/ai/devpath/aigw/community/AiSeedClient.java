package ai.devpath.aigw.community;

/** 커뮤니티 AI 시드 답변 LLM 추상화. 구현: Mock(CI/dev 기본), Ollama(dev), Claude(운영). 자유 텍스트. */
public interface AiSeedClient {
  SeedAnswer generate(SeedInput input);
  String providerName();
}
