package ai.devpath.aigw.provider;

/**
 * 한 {@code (feature, provider)} 의 복구 여부를 확인하는 최소 호출. 출력 1토큰 수준의
 * 최소 프롬프트를 쓴다 — 크레딧이 소진된 상태라면 즉시 429 로 떨어져 비용이 아예 없다.
 */
public interface ProviderProbe {

  String feature();

  String provider();

  /** 성공하면 조용히 반환하고, 실패하면 그 provider 의 예외를 그대로 던진다. */
  void ping();

  /**
   * 래치가 <b>닫혀 있어도</b> 주기적으로 핑할 대상인가(스펙 2026-10-03 §3.1-5). 폴백 자리의 Ollama 처럼
   * 죽은 것을 미리 알아야 하는 provider 만 {@code true} 다. 기본은 열린 래치를 닫는 용도로만 쓴다.
   */
  default boolean livenessTarget() {
    return false;
  }
}
