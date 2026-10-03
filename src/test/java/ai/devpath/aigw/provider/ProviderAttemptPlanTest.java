package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.devpath.aigw.provider.ProviderAttemptPlan.Attempt;
import ai.devpath.aigw.provider.ProviderAttemptPlan.Mode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ProviderAttemptPlanTest {

  private static Predicate<String> open(String... names) {
    Set<String> set = Set.of(names);
    return set::contains;
  }

  @Test
  void bothUsableClaudeFailsFastToOllama() {
    assertEquals(
        List.of(new Attempt("claude", Mode.FAST), new Attempt("ollama", Mode.LAST_RESORT)),
        ProviderAttemptPlan.plan(List.of("claude", "ollama"), open()));
  }

  @Test
  void deadFallbackGivesClaudeTheLastResortBudget() {
    assertEquals(
        List.of(new Attempt("claude", Mode.LAST_RESORT)),
        ProviderAttemptPlan.plan(List.of("claude", "ollama"), open("ollama")));
  }

  @Test
  void blockedPrimaryLeavesOnlyTheFallback() {
    assertEquals(
        List.of(new Attempt("ollama", Mode.LAST_RESORT)),
        ProviderAttemptPlan.plan(List.of("claude", "ollama"), open("claude")));
  }

  @Test
  void everythingBlockedCallsThePrimaryOnceAnyway() {
    // 폴백을 끈 상태(체인 길이 1)에는 래치가 없어 매번 1순위를 부른다 — 그것과 같아야 한다.
    assertEquals(
        List.of(new Attempt("claude", Mode.LAST_RESORT)),
        ProviderAttemptPlan.plan(List.of("claude", "ollama"), open("claude", "ollama")));
  }

  @Test
  void aSingleProviderIsAlwaysTheLastResort() {
    assertEquals(
        List.of(new Attempt("claude", Mode.LAST_RESORT)),
        ProviderAttemptPlan.plan(List.of("claude"), open()));
  }

  @Test
  void onlyTheLastUsableProviderIsTheLastResort() {
    assertEquals(
        List.of(new Attempt("claude", Mode.FAST), new Attempt("third", Mode.LAST_RESORT)),
        ProviderAttemptPlan.plan(List.of("claude", "ollama", "third"), open("ollama")));
  }

  @Test
  void rejectsAnEmptyChain() {
    assertThrows(IllegalArgumentException.class,
        () -> ProviderAttemptPlan.plan(List.of(), open()));
  }

  @Test
  void consultsTheLatchOncePerProvider() {
    // Review Focus 4: 요청 도중 래치가 바뀌어도 한 요청의 계획은 한 번 읽은 상태로 일관된다.
    Map<String, Integer> reads = new HashMap<>();
    Predicate<String> counting = name -> {
      reads.merge(name, 1, Integer::sum);
      return false;
    };

    ProviderAttemptPlan.plan(List.of("claude", "ollama"), counting);

    assertEquals(Map.of("claude", 1, "ollama", 1), reads);
  }
}
