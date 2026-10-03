package ai.devpath.aigw.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * provider 체인의 <b>순서만</b> 결정한다. 상태(차단 여부)는 {@link ProviderLatch} 가 갖는다.
 *
 * <p>원래 {@code MentorClientConfig.orderedChain} 이었다. 순수 static 함수라 그대로 올렸고,
 * 멘토는 계속 이것을 부른다.
 */
public final class ProviderChain {

  private ProviderChain() {}

  /** provider + fallback CSV 순서로, available 에 존재하는 것만, 중복 제거해 반환한다. */
  public static <T> List<T> ordered(String provider, String fallbackCsv, Map<String, T> available) {
    List<T> chain = new ArrayList<>();
    for (String name : requestedNames(provider, fallbackCsv)) {
      T c = available.get(name);
      if (c != null) chain.add(c);
    }
    return chain;
  }

  /**
   * {@link #ordered} 와 같은 순서를 <b>provider 이름까지 유지해서</b> 돌려준다.
   * 래치는 {@code (feature, provider)} 로 조회하므로 체인이 이름을 알아야 한다.
   */
  public static <T> LinkedHashMap<String, T> orderedMap(
      String provider, String fallbackCsv, Map<String, T> available) {
    LinkedHashMap<String, T> chain = new LinkedHashMap<>();
    for (String name : requestedNames(provider, fallbackCsv)) {
      T c = available.get(name);
      if (c != null) chain.put(name, c);
    }
    return chain;
  }

  /**
   * provider + fallback 이 <b>지정한</b> provider 개수(공백·중복 제거). 가용 여부는 보지 않는다 —
   * 「체인을 운영할 의도인가」만 묻는 자리에서 쓴다. 가용 목록을 아직 만들 수 없는 곳
   * (예: 그 가용 목록의 재료인 {@code AnthropicClient} 빈 정의)에서도 답을 낼 수 있어야 한다.
   */
  public static int requestedCount(String provider, String fallbackCsv) {
    return requestedNames(provider, fallbackCsv).size();
  }

  /**
   * {@code name} 이 체인의 1순위가 아닌 자리(폴백)에 있는가. {@link #ordered} 와 같은 규칙
   * (trim·빈 값·중복 제거)으로 판단한다 — 탐색 빈의 조건과 실제 체인이 어긋나지 않게.
   */
  public static boolean isFallback(String provider, String fallbackCsv, String name) {
    return requestedNames(provider, fallbackCsv).indexOf(name) > 0;
  }

  /** provider 를 맨 앞에 두고 fallback CSV 를 이어 붙인 뒤 공백·빈 값·중복을 제거한 순서. */
  private static List<String> requestedNames(String provider, String fallbackCsv) {
    List<String> order = new ArrayList<>();
    order.add(provider == null ? "" : provider.trim());
    if (fallbackCsv != null) {
      for (String f : fallbackCsv.split(",")) {
        String t = f.trim();
        if (!t.isEmpty()) order.add(t);
      }
    }
    List<String> names = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String name : order) {
      if (name.isEmpty() || !seen.add(name)) continue;
      names.add(name);
    }
    return names;
  }
}
