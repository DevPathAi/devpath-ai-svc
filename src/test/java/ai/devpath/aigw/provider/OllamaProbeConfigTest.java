package ai.devpath.aigw.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OllamaProbeConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(OllamaProbeConfig.class);

  @Test
  void registersALivenessProbeForEachFeatureWhoseFallbackIsOllama() {
    runner
        .withPropertyValues(
            "devpath.review.provider=claude", "devpath.review.fallback=ollama",
            "devpath.community-seed.provider=claude", "devpath.community-seed.fallback=ollama",
            "devpath.retention.provider=claude", "devpath.retention.fallback=ollama",
            "devpath.review.ollama-base-url=http://ollama-gpu.devpath.svc:11434",
            "devpath.review.ollama-model=qwen2.5:7b")
        .run(context -> {
          Map<String, ProviderProbe> probes = context.getBeansOfType(ProviderProbe.class);
          assertThat(probes).hasSize(3);
          assertThat(probes.values()).allMatch(ProviderProbe::livenessTarget);
          assertThat(probes.values()).extracting(ProviderProbe::feature)
              .containsExactlyInAnyOrder("review", "community-seed", "retention");
        });
  }

  @Test
  void registersNothingWithoutAFallback() {
    runner
        .withPropertyValues(
            "devpath.review.provider=claude", "devpath.community-seed.provider=claude",
            "devpath.retention.provider=claude")
        .run(context -> assertThat(context.getBeansOfType(ProviderProbe.class)).isEmpty());
  }

  @Test
  void registersNothingWhenOllamaIsThePrimary() {
    runner
        .withPropertyValues("devpath.review.provider=ollama", "devpath.review.fallback=claude")
        .run(context -> assertThat(context.getBeansOfType(ProviderProbe.class)).isEmpty());
  }

  @Test
  void registersNothingForAMockFeature() {
    // provider=mock 이면 ClientConfig 가 체인을 만들지 않는다 — 탐색할 폴백이 없다.
    runner
        .withPropertyValues("devpath.review.provider=mock", "devpath.review.fallback=ollama")
        .run(context -> assertThat(context.getBeansOfType(ProviderProbe.class)).isEmpty());
  }
}
