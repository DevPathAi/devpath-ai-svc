# 멘토 Ollama 우선 + 폴백 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 멘토 응답을 Ollama 우선으로 하고, 방출 전 실패 시 순서형 폴백(기본 `Ollama → Claude → mock`)으로 전환해 멘토가 항상 응답하게 한다.

**Architecture:** 기존 `AiMentorClient`(스트리밍 인터페이스)·3 구현체를 유지하되, `@ConditionalOnProperty` 단일활성 배선을 **`MentorClientConfig` 팩토리**로 이관한다. 신규 `FallbackMentorClient`가 delegate를 순서대로 시도하되 **첫 토큰 방출 후에는 폴백하지 않는다**. 실제 응답 provider는 ThreadLocal로 기록한다.

**Tech Stack:** Java 21, Spring Boot 4.0.7, Gradle(Kotlin DSL), JUnit 5, AssertJ, Mockito.

## Global Constraints

- 패키지: `ai.devpath.aigw.mentor`. 빌드/테스트: `./gradlew build` · `./gradlew test`. 실행 포트 8080.
- `AiMentorClient` 인터페이스(변경 금지): `void stream(MentorInput input, java.util.function.Consumer<String> tokenSink)` · `String providerName()`.
- `MentorInput` = `record MentorInput(String question, String contextText)`.
- provider 이름 상수(각 client `providerName()` 반환값): Ollama=`"OLLAMA"`, Claude=`"CLAUDE"`, Mock=`"MOCK"`.
- 신규 설정 키 `devpath.mentor.fallback`(순서형 CSV, 기본 빈문자열). **빈값=기존 동작 무변경.**
- 비밀값(`ANTHROPIC_API_KEY`) 커밋 금지. Conventional Commits. 작업 브랜치 `feat/mentor-ollama-fallback`(이미 생성, origin/develop 분기).
- 절대조건: 추측 금지·Test-First·자화자찬 금지. 각 Task 끝에 커밋.

---

### Task 1: FallbackMentorClient (composite, 스트리밍 폴백)

**Files:**
- Create: `src/main/java/ai/devpath/aigw/mentor/FallbackMentorClient.java`
- Test: `src/test/java/ai/devpath/aigw/mentor/FallbackMentorClientTest.java`

**Interfaces:**
- Consumes: `AiMentorClient`, `MentorInput`.
- Produces: `class FallbackMentorClient implements AiMentorClient` — 생성자 `FallbackMentorClient(java.util.List<AiMentorClient> delegates)`. `stream()`은 delegate를 순서대로 시도(방출 전 실패만 다음으로), `providerName()`은 직전 `stream()`이 실제 응답한 delegate의 provider 이름을 동일 스레드에서 반환.

- [ ] **Step 1: 실패 테스트 작성**

`FallbackMentorClientTest.java`:

```java
package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class FallbackMentorClientTest {

  /** 지정 토큰을 방출하거나, 지정 개수만큼 방출 후 예외를 던지는 가짜 delegate. */
  static final class FakeClient implements AiMentorClient {
    final String name;
    final List<String> tokens;
    final int emitThenFail; // -1이면 실패 없음, N이면 N개 방출 후 예외
    FakeClient(String name, List<String> tokens, int emitThenFail) {
      this.name = name; this.tokens = tokens; this.emitThenFail = emitThenFail;
    }
    @Override public void stream(MentorInput input, Consumer<String> sink) {
      int i = 0;
      for (String t : tokens) {
        if (emitThenFail >= 0 && i >= emitThenFail) throw new RuntimeException(name + " failed");
        sink.accept(t); i++;
      }
      if (emitThenFail >= 0 && tokens.size() <= emitThenFail) return;
    }
    @Override public String providerName() { return name; }
  }

  private final MentorInput input = new MentorInput("q", "ctx");

  @Test
  void primarySucceeds_noFallback_reportsPrimary() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("a", "b"), -1);
    FakeClient secondary = new FakeClient("MOCK", List.of("x"), -1);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));
    List<String> out = new ArrayList<>();

    client.stream(input, out::add);

    assertThat(out).containsExactly("a", "b");
    assertThat(client.providerName()).isEqualTo("OLLAMA");
  }

  @Test
  void primaryFailsBeforeEmit_fallsBackToSecondary() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("nope"), 0); // 방출 전 실패
    FakeClient secondary = new FakeClient("MOCK", List.of("x", "y"), -1);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));
    List<String> out = new ArrayList<>();

    client.stream(input, out::add);

    assertThat(out).containsExactly("x", "y");
    assertThat(client.providerName()).isEqualTo("MOCK");
  }

  @Test
  void primaryFailsAfterEmit_rethrows_noFallback() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("a", "b", "c"), 1); // 1개 방출 후 실패
    FakeClient secondary = new FakeClient("MOCK", List.of("x"), -1);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));
    List<String> out = new ArrayList<>();

    assertThatThrownBy(() -> client.stream(input, out::add))
        .isInstanceOf(RuntimeException.class);
    assertThat(out).containsExactly("a"); // primary가 방출한 토큰만, 폴백 안 함
  }

  @Test
  void allFailBeforeEmit_throws() {
    FakeClient primary = new FakeClient("OLLAMA", List.of("n"), 0);
    FakeClient secondary = new FakeClient("CLAUDE", List.of("n"), 0);
    FallbackMentorClient client = new FallbackMentorClient(List.of(primary, secondary));

    assertThatThrownBy(() -> client.stream(input, t -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void emptyDelegates_rejected() {
    assertThatThrownBy(() -> new FallbackMentorClient(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "ai.devpath.aigw.mentor.FallbackMentorClientTest" --offline`
Expected: FAIL(컴파일 에러 — FallbackMentorClient 없음).

- [ ] **Step 3: 최소 구현**

`FallbackMentorClient.java`:

```java
package ai.devpath.aigw.mentor;

import java.util.List;
import java.util.function.Consumer;

/**
 * 순서형 폴백 멘토 클라이언트. delegate를 순서대로 시도하되, 어떤 delegate가 토큰을 하나라도
 * 방출한 뒤 실패하면 폴백하지 않고 예외를 전파한다(스트리밍 중간 전환 불가). 실제 응답한 provider는
 * ThreadLocal에 기록해 stream() 직후 같은 스레드의 providerName() 호출이 정확히 읽는다.
 */
public class FallbackMentorClient implements AiMentorClient {

  private static final ThreadLocal<String> SERVED = new ThreadLocal<>();

  private final List<AiMentorClient> delegates;

  public FallbackMentorClient(List<AiMentorClient> delegates) {
    if (delegates == null || delegates.isEmpty()) {
      throw new IllegalArgumentException("delegates must not be empty");
    }
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public void stream(MentorInput input, Consumer<String> tokenSink) {
    SERVED.remove();
    boolean[] emitted = {false};
    Consumer<String> guarded = t -> { emitted[0] = true; tokenSink.accept(t); };
    RuntimeException last = null;
    for (AiMentorClient d : delegates) {
      try {
        d.stream(input, guarded);
        SERVED.set(d.providerName());
        return;
      } catch (RuntimeException e) {
        if (emitted[0]) throw e; // 이미 스트리밍 시작 → 폴백 불가
        last = e;                // 방출 전 실패 → 다음 delegate
      }
    }
    throw (last != null ? last : new IllegalStateException("no mentor delegate available"));
  }

  @Override
  public String providerName() {
    String s = SERVED.get();
    SERVED.remove();
    return s != null ? s : delegates.get(0).providerName();
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "ai.devpath.aigw.mentor.FallbackMentorClientTest" --offline`
Expected: PASS(5 케이스).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/ai/devpath/aigw/mentor/FallbackMentorClient.java \
        src/test/java/ai/devpath/aigw/mentor/FallbackMentorClientTest.java
git commit -m "feat(mentor): 순서형 폴백 FallbackMentorClient(방출 전 실패만 폴백)"
```

---

### Task 2: 배선 이관 — MentorClientConfig 팩토리 + 설정 키 + 조건 정리

**Files:**
- Create: `src/main/java/ai/devpath/aigw/mentor/MentorClientConfig.java`
- Test: `src/test/java/ai/devpath/aigw/mentor/MentorClientConfigTest.java`
- Modify: `src/main/java/ai/devpath/aigw/mentor/OllamaMentorClient.java` (`@Component`·`@ConditionalOnProperty` + 관련 import 제거)
- Modify: `src/main/java/ai/devpath/aigw/mentor/ClaudeMentorClient.java` (`@Component`·`@ConditionalOnProperty` + import 제거)
- Modify: `src/main/java/ai/devpath/aigw/mentor/MockMentorClient.java` (`@Component`·`@ConditionalOnProperty` + import 제거)
- Modify: `src/main/java/ai/devpath/aigw/mentor/MentorClaudeClientConfig.java` (조건을 키 존재 기반으로)
- Modify: `src/main/resources/application.yml` (`devpath.mentor.fallback` 추가)

**Interfaces:**
- Consumes: Task 1 `FallbackMentorClient`; 기존 `OllamaMentorClient`/`ClaudeMentorClient`/`MockMentorClient`/`MentorPromptBuilder`; `com.anthropic.client.AnthropicClient`.
- Produces: `MentorClientConfig`에 **package-private static** `static java.util.List<AiMentorClient> orderedChain(String provider, String fallbackCsv, java.util.Map<String,AiMentorClient> available)` — provider+fallback 순서로 available에 존재하는 client만, 중복 제거해 반환. `@Bean AiMentorClient mentorClient(...)`가 이 결과를 단일이면 직접, 복수면 `FallbackMentorClient`로 감싸 노출.

- [ ] **Step 1: orderedChain 실패 테스트 작성**

`MentorClientConfigTest.java`:

```java
package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MentorClientConfigTest {

  static final class Stub implements AiMentorClient {
    final String name;
    Stub(String name) { this.name = name; }
    @Override public void stream(MentorInput in, Consumer<String> sink) {}
    @Override public String providerName() { return name; }
  }

  private Map<String, AiMentorClient> available(String... names) {
    Map<String, AiMentorClient> m = new LinkedHashMap<>();
    for (String n : names) m.put(n, new Stub(n));
    return m;
  }

  private List<String> names(List<AiMentorClient> chain) {
    return chain.stream().map(AiMentorClient::providerName).toList();
  }

  @Test
  void fullChain_ollamaClaudeMock() {
    var chain = MentorClientConfig.orderedChain(
        "ollama", "claude,mock", available("ollama", "claude", "mock"));
    assertThat(names(chain)).containsExactly("ollama", "claude", "mock");
  }

  @Test
  void claudeUnavailable_isFilteredOut() {
    var chain = MentorClientConfig.orderedChain(
        "ollama", "claude,mock", available("ollama", "mock")); // claude 없음(무키)
    assertThat(names(chain)).containsExactly("ollama", "mock");
  }

  @Test
  void singleProvider_emptyFallback() {
    var chain = MentorClientConfig.orderedChain(
        "mock", "", available("ollama", "mock"));
    assertThat(names(chain)).containsExactly("mock");
  }

  @Test
  void deduplicatesPreservingOrder() {
    var chain = MentorClientConfig.orderedChain(
        "ollama", "ollama, mock", available("ollama", "mock"));
    assertThat(names(chain)).containsExactly("ollama", "mock");
  }

  @Test
  void unknownPrimary_fallsToAvailableFallback() {
    var chain = MentorClientConfig.orderedChain(
        "claude", "mock", available("ollama", "mock")); // claude 없음
    assertThat(names(chain)).containsExactly("mock");
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "ai.devpath.aigw.mentor.MentorClientConfigTest" --offline`
Expected: FAIL(MentorClientConfig 없음).

- [ ] **Step 3: MentorClientConfig 구현**

`MentorClientConfig.java`:

```java
package ai.devpath.aigw.mentor;

import com.anthropic.client.AnthropicClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 멘토 클라이언트 단일 진입점 조립. provider(primary) + devpath.mentor.fallback(순서형 CSV)을
 * 가용 provider로 필터링해 체인을 만든다. 체인 길이 1이면 그 client, 2 이상이면 FallbackMentorClient.
 * claude는 AnthropicClient 빈(키 존재)이 있을 때만 가용.
 */
@Configuration
public class MentorClientConfig {

  @Bean
  public AiMentorClient mentorClient(
      @Value("${devpath.mentor.provider:mock}") String provider,
      @Value("${devpath.mentor.fallback:}") String fallbackCsv,
      @Value("${devpath.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
      @Value("${devpath.mentor.ollama-model:qwen2.5:7b}") String ollamaModel,
      @Value("${devpath.mentor.timeout:PT60S}") Duration timeout,
      @Value("${devpath.mentor.claude-model:claude-sonnet-4-6}") String claudeModel,
      MentorPromptBuilder prompts, JsonMapper jsonMapper,
      ObjectProvider<AnthropicClient> anthropicClientProvider) {

    Map<String, AiMentorClient> available = new LinkedHashMap<>();
    available.put("ollama",
        new OllamaMentorClient(ollamaBaseUrl, ollamaModel, timeout, prompts, jsonMapper));
    available.put("mock", new MockMentorClient());
    AnthropicClient anthropic = anthropicClientProvider.getIfAvailable();
    if (anthropic != null) {
      available.put("claude", new ClaudeMentorClient(anthropic, claudeModel, prompts));
    }

    List<AiMentorClient> chain = orderedChain(provider, fallbackCsv, available);
    if (chain.isEmpty()) {
      chain = List.of(available.get("mock")); // 안전망: 최소 mock
    }
    return chain.size() == 1 ? chain.get(0) : new FallbackMentorClient(chain);
  }

  /** provider+fallback 순서로 available에 존재하는 client만, 중복 제거해 반환. */
  static List<AiMentorClient> orderedChain(String provider, String fallbackCsv,
      Map<String, AiMentorClient> available) {
    List<String> order = new ArrayList<>();
    order.add(provider == null ? "" : provider.trim());
    if (fallbackCsv != null) {
      for (String f : fallbackCsv.split(",")) {
        String t = f.trim();
        if (!t.isEmpty()) order.add(t);
      }
    }
    List<AiMentorClient> chain = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String name : order) {
      if (name.isEmpty() || !seen.add(name)) continue;
      AiMentorClient c = available.get(name);
      if (c != null) chain.add(c);
    }
    return chain;
  }
}
```

- [ ] **Step 4: orderedChain 테스트 통과 확인**

Run: `./gradlew test --tests "ai.devpath.aigw.mentor.MentorClientConfigTest" --offline`
Expected: PASS(5 케이스).

- [ ] **Step 5: 3 client에서 컴포넌트/조건 애너테이션 제거**

`OllamaMentorClient.java`: `@Component`와 `@ConditionalOnProperty(name = "devpath.mentor.provider", havingValue = "ollama")` 두 애너테이션을 클래스에서 제거하고, 이제 미사용이 되는 import 두 줄(`org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`, `org.springframework.stereotype.Component`)을 삭제한다. 생성자·로직은 그대로.

`ClaudeMentorClient.java`: 동일하게 `@Component`·`@ConditionalOnProperty(...havingValue = "claude")` 및 두 import 제거. `@Qualifier`/`@Value` import·애너테이션은 유지(생성자 파라미터에 사용).

`MockMentorClient.java`: 동일하게 `@Component`·`@ConditionalOnProperty(...havingValue = "mock", matchIfMissing = true)` 및 두 import 제거.

- [ ] **Step 6: MentorClaudeClientConfig 조건을 키 존재 기반으로**

`MentorClaudeClientConfig.java`에서 클래스 애너테이션을
`@ConditionalOnProperty(name = "devpath.mentor.provider", havingValue = "claude")` →
`@ConditionalOnExpression("'${ANTHROPIC_API_KEY:}' != ''")` 로 교체하고, import를
`org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` →
`org.springframework.boot.autoconfigure.condition.ConditionalOnExpression` 로 교체한다.
(효과: `ANTHROPIC_API_KEY` 환경변수가 있을 때만 `mentorAnthropicClient` 빈 생성 → 팩토리가 claude를 체인에 포함. 무키/CI는 빈 없음 → claude 자동 제외, 부팅 안전.)

- [ ] **Step 7: application.yml에 fallback 키 추가**

`src/main/resources/application.yml`의 `mentor:` 블록(현재 provider/claude-model/ollama-model/enabled/timeout)에서 `provider` 바로 아래 한 줄을 추가한다:

```yaml
  mentor:
    provider: ${MENTOR_PROVIDER:mock}
    fallback: ${MENTOR_FALLBACK:}
    claude-model: ${MENTOR_CLAUDE_MODEL:claude-sonnet-4-6}
    ollama-model: ${MENTOR_OLLAMA_MODEL:qwen2.5:7b}
    enabled: ${MENTOR_ENABLED:true}
    timeout: ${MENTOR_TIMEOUT:PT60S}
```

- [ ] **Step 8: 전체 빌드·테스트 green 확인**

Run: `./gradlew build --offline`
Expected: BUILD SUCCESSFUL. 특히 기존 멘토 테스트(`MentorServiceTest`·`MentorSseIntegrationTest`·`MentorKillSwitchTest`·`OllamaMentorClientTest`·`ClaudeMentorClientTest`·`MockMentorClientTest`)와 신규 2개 테스트가 모두 통과. (실패 시 근본원인 규명 후 수정 — 추측 금지.)

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/ai/devpath/aigw/mentor/MentorClientConfig.java \
        src/test/java/ai/devpath/aigw/mentor/MentorClientConfigTest.java \
        src/main/java/ai/devpath/aigw/mentor/OllamaMentorClient.java \
        src/main/java/ai/devpath/aigw/mentor/ClaudeMentorClient.java \
        src/main/java/ai/devpath/aigw/mentor/MockMentorClient.java \
        src/main/java/ai/devpath/aigw/mentor/MentorClaudeClientConfig.java \
        src/main/resources/application.yml
git commit -m "feat(mentor): MentorClientConfig 팩토리로 배선 이관 + fallback 체인 설정"
```

---

### Task 3: 로컬 실증 + develop PR

**Files:** (문서/실행 — 코드 변경 없음. 필요 시 `docs/project-management/` 진행기록.)

**Interfaces:**
- Consumes: Task 1·2 산출물.

- [ ] **Step 1: 로컬 Ollama 기동 확인**

Run: `curl -s http://localhost:11434/api/tags` → `qwen2.5:7b` 포함 확인. 없으면 `ollama serve` + `ollama pull qwen2.5:7b`.
(참고: 로컬은 Windows이며 `ollama` 실행파일 경로가 PATH에 없을 수 있음 — `/c/Users/deepe/AppData/Local/Programs/Ollama` 추가.)

- [ ] **Step 2: ai-svc 로컬 기동(멘토 ollama 우선 + mock 폴백)**

Run(별도 셸): `MENTOR_PROVIDER=ollama MENTOR_FALLBACK=mock ./gradlew bootRun`
(주의: dev 프로파일 의존 인프라가 필요하면 최소분만. 목적은 멘토 SSE만 실증.)

- [ ] **Step 3: 멘토 SSE 실증 — Ollama 응답**

Run: `POST /ai-mentor/sessions`(JWT 필요 — 로컬 세션 토큰)로 질문 전송 → `token` 이벤트가 스트리밍되고 persistence의 provider가 `OLLAMA`인지 확인(DB 또는 로그).
Expected: 한국어 멘토 응답 토큰 스트림(로컬 qwen2.5:7b).

- [ ] **Step 4: 폴백 실증 — Ollama 미가동 시 mock**

Ollama를 잠시 중단(또는 `MENTOR_PROVIDER=ollama MENTOR_FALLBACK=mock`에서 base-url을 죽은 포트로)하고 동일 요청 → 방출 전 연결 실패 → **mock 응답 토큰** 수신, provider가 `MOCK` 기록 확인.
Expected: mock 고정 응답 스트림(멘토가 끊기지 않음).

- [ ] **Step 5: push + develop PR**

```bash
git push -u origin feat/mentor-ollama-fallback
gh pr create --base develop --head feat/mentor-ollama-fallback \
  --title "feat(mentor): 멘토 Ollama 우선 + 폴백(Ollama→Claude→mock)" \
  --body "설계 docs/superpowers/specs/2026-07-29-mentor-ollama-fallback-design.md 참조. 신규 devpath.mentor.fallback(빈값=무변경), MentorClientConfig 팩토리 배선 이관, FallbackMentorClient(방출 전 실패만 폴백). 운영 env는 AWS 재개 시 후속."
```
운영 반영(gitops `MENTOR_PROVIDER`/`MENTOR_FALLBACK`)은 이 워크스트림 밖(후속).

---

## Self-Review

**1. Spec coverage:** 스펙 §3.1(설정 fallback)=Task2 Step7, §3.2(팩토리 배선·무키 claude 제외)=Task2 Step3/5/6, §3.3(FallbackMentorClient·ThreadLocal)=Task1, §3.4(에러/영속 불변)=변경 없음(Task2 Step8 회귀 확인), §4(테스트: Fallback 4+빈검증·Config 5·기존 green·로컬 실증)=Task1·Task2 Step1/8·Task3. ✅

**2. Placeholder scan:** 모든 코드 스텝에 실제 코드/명령. 애너테이션 제거는 대상 파일·애너테이션·import를 구체 명시. TBD 없음. ✅

**3. Type consistency:** `AiMentorClient.stream(MentorInput, Consumer<String>)`·`providerName()`, `MentorInput(question, contextText)`, provider 상수(OLLAMA/CLAUDE/MOCK), `orderedChain(String,String,Map<String,AiMentorClient>)`, 클라이언트 생성자 시그니처(Ollama: baseUrl,model,Duration,MentorPromptBuilder,JsonMapper / Claude: AnthropicClient,model,MentorPromptBuilder / Mock: 무인자)가 실측과 일치. ✅

**4. 리스크:** 배선 리팩터가 기존 테스트에 미치는 영향 — per-client 테스트는 직접 `new`(무관), SSE/KillSwitch는 `AiMentorClient` 빈만 필요(팩토리가 mock 제공)로 실측 확인됨. Task2 Step8 전체 green이 최종 가드. ✅
