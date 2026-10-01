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
    List<String> order = new ArrayList<>();
    order.add(provider == null ? "" : provider.trim());
    if (fallbackCsv != null) {
      for (String f : fallbackCsv.split(",")) {
        String t = f.trim();
        if (!t.isEmpty()) order.add(t);
      }
    }
    List<T> chain = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String name : order) {
      if (name.isEmpty() || !seen.add(name)) continue;
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
    LinkedHashMap<String, String> identity = new LinkedHashMap<>();
    for (String name : available.keySet()) identity.put(name, name);
    LinkedHashMap<String, T> chain = new LinkedHashMap<>();
    for (String name : ordered(provider, fallbackCsv, identity)) {
      chain.put(name, available.get(name));
    }
    return chain;
  }
}
