package ai.devpath.aigw.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 한 요청의 provider 시도 계획. 체인 순서와 래치 상태만으로 정한다(스펙 2026-10-03 §3.1-1).
 *
 * <ul>
 *   <li>래치가 열린 provider 는 건너뛴다.</li>
 *   <li>뒤에 래치가 닫힌 provider 가 남아 있는 시도는 {@link Mode#FAST} — 실패를 바로 넘긴다
 *       (#83 보정 ②: SDK 재시도가 429 를 삼켜 래치 판정을 왜곡하지 않게).</li>
 *   <li>뒤에 쓸 수 있는 provider 가 없는 시도는 {@link Mode#LAST_RESORT} — 폴백을 끈 상태와 같은
 *       재시도 예산을 쓴다.</li>
 *   <li>전부 막혔으면 1순위를 {@link Mode#LAST_RESORT} 로 한 번 시도한다(래치 무시). 폴백을 끈
 *       상태에는 래치가 없어 매번 1순위를 부르기 때문이다. 이 시도는 {@code forced} 다 — 그 실패는
 *       래치에 다시 기록하지 않는다(이미 열린 래치의 사다리만 키워 회복을 늦춘다).</li>
 * </ul>
 *
 * <p>래치는 provider 마다 <b>한 번만</b> 읽는다 — 요청 도중 상태가 바뀌어도 계획이 일관된다.
 */
public final class ProviderAttemptPlan {

  public enum Mode { FAST, LAST_RESORT }

  /**
   * {@code forced} = 전부 막혀 래치를 무시하고 부르는 1순위 시도. 호출하는 쪽은 그 실패를 래치에 다시
   * 기록하지 않는다 — 기록하면 이미 열린 래치의 사다리가 요청마다 자라(5→10→…60분), 폴백이 돌아온 뒤에도
   * 회복된 1순위를 그만큼 건너뛴다(최종 리뷰 I-1). 성공은 기록한다(1순위가 돌아왔다는 증거다).
   */
  public record Attempt(String name, Mode mode, boolean forced) {

    public Attempt(String name, Mode mode) {
      this(name, mode, false);
    }
  }

  private ProviderAttemptPlan() {}

  public static List<Attempt> plan(List<String> chain, Predicate<String> isOpen) {
    if (chain == null || chain.isEmpty()) {
      throw new IllegalArgumentException("chain must not be empty");
    }
    List<String> usable = new ArrayList<>();
    for (String name : chain) {
      if (!isOpen.test(name)) usable.add(name);
    }
    if (usable.isEmpty()) {
      return List.of(new Attempt(chain.get(0), Mode.LAST_RESORT, true));
    }
    List<Attempt> attempts = new ArrayList<>(usable.size());
    for (int i = 0; i < usable.size(); i++) {
      Mode mode = i < usable.size() - 1 ? Mode.FAST : Mode.LAST_RESORT;
      attempts.add(new Attempt(usable.get(i), mode));
    }
    return List.copyOf(attempts);
  }
}
