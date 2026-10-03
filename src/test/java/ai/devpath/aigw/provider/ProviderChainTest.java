package ai.devpath.aigw.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderChainTest {

  private Map<String, String> available() {
    Map<String, String> available = new LinkedHashMap<>();
    available.put("ollama", "OLLAMA");
    available.put("claude", "CLAUDE");
    available.put("mock", "MOCK");
    return available;
  }

  @Test
  void putsThePrimaryFirstThenTheFallbacksInOrder() {
    assertEquals(List.of("CLAUDE", "OLLAMA"),
        ProviderChain.ordered("claude", "ollama", available()));
  }

  @Test
  void dropsNamesThatAreNotAvailable() {
    assertEquals(List.of("CLAUDE"),
        ProviderChain.ordered("claude", "gemini", available()));
  }

  @Test
  void dropsADuplicateOfThePrimary() {
    // Review Focus 1: REVIEW_FALLBACK=claude with REVIEW_PROVIDER=claude must not duplicate.
    assertEquals(List.of("CLAUDE"),
        ProviderChain.ordered("claude", "claude", available()));
  }

  @Test
  void trimsWhitespaceAndDropsEmptyEntries() {
    // Review Focus 2: " ollama , , claude " must parse to two entries.
    assertEquals(List.of("CLAUDE", "OLLAMA"),
        ProviderChain.ordered(" claude ", " ollama , , ", available()));
  }

  @Test
  void returnsAnEmptyListWhenNothingMatches() {
    assertEquals(List.of(), ProviderChain.ordered("gemini", null, available()));
  }

  @Test
  void toleratesANullPrimary() {
    assertEquals(List.of("OLLAMA"), ProviderChain.ordered(null, "ollama", available()));
  }

  @Test
  void orderedMapKeepsTheProviderNamesInChainOrder() {
    // Fallback*Client 가 래치를 조회하려면 값이 아니라 이름이 필요하다.
    assertEquals(List.of("claude", "ollama"),
        List.copyOf(ProviderChain.orderedMap("claude", "ollama", available()).keySet()));
    assertEquals(List.of("CLAUDE", "OLLAMA"),
        List.copyOf(ProviderChain.orderedMap("claude", "ollama", available()).values()));
  }

  @Test
  void orderedMapIsEmptyWhenNothingMatches() {
    assertEquals(Map.of(), ProviderChain.orderedMap("gemini", null, available()));
  }

  @Test
  void ollamaInTheFallbackSlotIsAFallback() {
    assertTrue(ProviderChain.isFallback("claude", "ollama", "ollama"));
  }

  @Test
  void thePrimaryIsNotAFallback() {
    assertFalse(ProviderChain.isFallback("ollama", "claude", "ollama"));
  }

  @Test
  void anAbsentProviderIsNotAFallback() {
    assertFalse(ProviderChain.isFallback("claude", "", "ollama"));
  }

  @Test
  void isFallbackTrimsAndDeduplicates() {
    // Review Focus 1: 체인 순서와 같은 규칙.
    assertTrue(ProviderChain.isFallback(" claude ", " ollama , ollama ,", "ollama"));
    assertFalse(ProviderChain.isFallback("claude", " claude , ", "claude"));
  }
}
