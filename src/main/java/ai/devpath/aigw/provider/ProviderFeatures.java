package ai.devpath.aigw.provider;

/**
 * 래치·복구 탐색기가 공유하는 기능 키. {@link ProviderLatch} 는 {@code (feature, provider)} 로
 * 조회하므로 폴백 래퍼와 탐색기가 <b>같은 문자열</b>을 써야 한다.
 *
 * <p>양쪽이 각자 리터럴을 들고 있으면 한쪽의 오타가 그 기능의 복구 탐색을 조용히 끈다 —
 * 래치는 열리는데 아무도 닫지 않고, 어떤 테스트도 실패하지 않는다.
 */
public final class ProviderFeatures {

  public static final String REVIEW = "review";
  public static final String COMMUNITY_SEED = "community-seed";
  public static final String RETENTION = "retention";

  private ProviderFeatures() {}
}
