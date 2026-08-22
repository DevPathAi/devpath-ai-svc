# 멘토 Ollama 우선 + 폴백 설계 (Ollama → Claude → mock)

- 날짜: 2026-07-29
- 상태: **사용자 승인**(구현 전 — 이 spec 리뷰 후 플랜 작성)
- 대상 레포: `devpath-ai-svc`(`ai.devpath.aigw.mentor`)
- 우선순위: 로컬 4이슈 워크스트림 **②**(멘토 Ollama 우선) — 다음: 커뮤니티 FREE/FEEDBACK 보드

## 1. 배경 (코드 실측)

사용자 피드백: "멘토 응답을 Ollama 우선으로." 현재 멘토는 provider를 **한 번에 하나만** 선택한다:

- `AiMentorClient` 인터페이스: `void stream(MentorInput, Consumer<String> tokenSink)` + `String providerName()`. 토큰을 tokenSink로 push(SSE 스트리밍).
- 3 구현체 모두 `@Component @ConditionalOnProperty("devpath.mentor.provider", havingValue=...)`:
  - `OllamaMentorClient`(ollama, `/api/chat` NDJSON 스트림), `ClaudeMentorClient`(claude, Anthropic 스트림, `@Qualifier("mentorAnthropicClient")` AnthropicClient 주입), `MockMentorClient`(mock, 기본 `matchIfMissing=true`).
- `MentorService`가 단일 `AiMentorClient`를 주입받아 `stream()` 호출 → 성공 시 `providerName()`을 persistence에 기록, 예외 시 `saveFailed("LLM_FAILED")` + SSE 에러.
- 엔드포인트 `POST /ai-mentor/sessions` → `SseEmitter`, `mentorExecutor` 전용 스레드에서 `MentorService.streamAnswer` 실행.
- AnthropicClient 빈은 `MentorClaudeClientConfig`(@ConditionalOnProperty=claude)에서 `ANTHROPIC_API_KEY` 환경변수로 생성.

즉 **Ollama 우선 + 폴백은 provider 단일 선택 구조로는 불가** — 폴백 체인이 필요하고, 이는 배선 재구성 + 스트리밍 폴백 안전장치를 요구한다.

## 2. 목표 / 비목표

**목표**
- 멘토 응답을 **Ollama 우선**으로 하고, Ollama 실패/미가동 시 **순서형 폴백**(기본 체인 `Ollama → Claude → mock`)으로 전환해 멘토가 항상 응답하게 한다.
- 기존 단일 provider 동작(claude/mock/ollama 단독)과 기존 테스트를 **보존**한다(폴백 미설정 시 무변경).
- 절대조건 준수: 추측 금지·Test-First·자화자찬 금지·작업 브랜치.

**비목표**
- 스트리밍 **중간** 폴백(첫 토큰 방출 후 provider 전환) — 불가능(사용자가 부분 출력을 이미 봄). 방출 전 실패만 폴백.
- 멘토 프롬프트/컨텍스트/레퍼런스 로직 변경 — 범위 밖.
- 운영 배포 반영(gitops env) — 이 워크스트림은 코드 + 로컬 검증까지. 운영 env는 후속.

## 3. 설계

### 3.1 설정 (`devpath.mentor`)
- `provider: ${MENTOR_PROVIDER:mock}` — primary provider(그대로).
- **신규** `fallback: ${MENTOR_FALLBACK:}` — 순서형 CSV 폴백 provider 목록(예 `claude,mock`). **빈값 = 폴백 없음 = 기존 동작 무변경.**
- 기존 유지: `ollama-model`, `claude-model`, `timeout`, `devpath.ollama.base-url`.
- 로컬 활성 예: `MENTOR_PROVIDER=ollama`, `MENTOR_FALLBACK=claude,mock`.

### 3.2 배선 팩토리 (`MentorClientConfig`)
`@ConditionalOnProperty` 단일활성을 **@Configuration 팩토리**로 이관해 단일 `AiMentorClient` 빈을 조립한다.

1. 가용 provider→client 구성:
   - `ollama` → `OllamaMentorClient`(항상 생성 가능).
   - `mock` → `MockMentorClient`(항상).
   - `claude` → **AnthropicClient 빈이 존재할 때만**(`ObjectProvider<AnthropicClient>`로 조회). 없으면(무키) 체인에서 **자동 제외**.
2. `chain = [provider] + fallback목록`, 가용 provider만 필터, 중복 제거(순서 보존).
3. 조립:
   - chain 비었거나 길이 1 → 그 client **직접 반환**(래핑 없음, 기존 경로).
   - 길이 ≥2 → `FallbackMentorClient(chain)` 반환.
- **배선 결정(명확화)**: 기존 3 client(`OllamaMentorClient`·`ClaudeMentorClient`·`MockMentorClient`)에서 **`@Component`와 `@ConditionalOnProperty`를 모두 제거**한다(그대로 두면 조건 제거 시 3빈 동시활성 → `MentorService`의 단일 `AiMentorClient` 주입이 모호해짐). 이 클래스들은 **오직 `MentorClientConfig` 팩토리에서만 인스턴스화**되며, 팩토리가 `@Bean AiMentorClient` 하나만 노출한다.
- AnthropicClient 빈(`mentorAnthropicClient`)은 `ANTHROPIC_API_KEY` 존재 시에만 생성되도록 `MentorClaudeClientConfig`를 정리한다(무키 부팅 안전). 팩토리는 `ObjectProvider<AnthropicClient>`로 존재 여부를 확인해 claude delegate 포함/제외를 결정한다.

### 3.3 FallbackMentorClient (신규)
`AiMentorClient` 구현. 순서형 `List<AiMentorClient> delegates` 보유.

```
void stream(input, sink):
  emitted = false (스레드 지역 플래그)
  guarded = t -> { emitted = true; sink.accept(t) }
  last = null
  for d in delegates:
    try { d.stream(input, guarded); SERVED.set(d.providerName()); return }
    catch (RuntimeException e):
      if (emitted) throw e          # 이미 스트리밍 → 폴백 불가
      last = e                       # 방출 전 실패 → 다음 delegate
  throw last                         # 전부 방출 전 실패

String providerName():               # stream 직후 동일 스레드에서 호출
  s = SERVED.get(); SERVED.remove(); return s != null ? s : delegates[0].providerName()
```

- **실제 응답 provider 기록**: `SERVED`는 `ThreadLocal<String>`. `MentorService`가 `mentorExecutor` 동일 스레드에서 `stream()` 직후 `providerName()`을 호출하므로 스레드 안전. `stream()` 진입 시 `SERVED.remove()`로 이전 값 정리.
- `MentorService`·`AiMentorClient` 인터페이스 **변경 없음**.
- mock이 체인 말단이면 사실상 항상 성공 → 멘토 항상 응답.

### 3.4 에러/영속 (불변)
- 전부(방출 전) 실패 → `FallbackMentorClient`가 throw → `MentorService` catch → `saveFailed("LLM_FAILED")` + SSE 에러(기존과 동일). mock 포함 체인이면 거의 발생 안 함.
- 방출 후 실패 → rethrow → 동일 경로(부분 출력 후 에러).

## 4. 테스트 (Test-First)

- **FallbackMentorClient 단위**(가짜 delegate 사용):
  1. primary 성공 → 폴백 미호출, `providerName()`=primary.
  2. primary가 **방출 전** throw → 2차 delegate로 폴백, served=2차.
  3. primary가 **토큰 방출 후** throw → rethrow(폴백 안 함), 방출된 토큰 sink에 남음.
  4. 전부 방출 전 throw → 최종 throw.
- **MentorClientConfig 단위/슬라이스**:
  - `provider=ollama, fallback=claude,mock`, 무키 → chain=[ollama,mock](claude 제외).
  - 유키(AnthropicClient 빈 존재) → [ollama,claude,mock].
  - `provider=mock, fallback=`(빈) → MockMentorClient **직접 반환**(비래핑).
- 기존 per-client 테스트(`OllamaMentorClientTest`·`ClaudeMentorClientTest`·`MockMentorClientTest`·`MentorServiceTest`·`MentorSseIntegrationTest`) **green 유지**.
- 로컬 실증: `MENTOR_PROVIDER=ollama MENTOR_FALLBACK=claude,mock`(무키)로 `POST /ai-mentor/sessions` 호출 → Ollama 토큰 스트림 수신(로컬 Ollama qwen2.5:7b). Ollama 중단 상태 → mock 폴백 응답 수신.

## 5. 리스크

- **배선 리팩터가 기존 테스트 건드림**: 3 client의 `@ConditionalOnProperty` 제거 시 컨텍스트 로딩/빈 선택에 의존한 테스트가 있으면 조정 필요. → 팩토리 전용 생성으로 통일하고 per-client 테스트는 직접 인스턴스화 유지. 변경 후 전체 `./gradlew test` green 확인.
- **무키 Claude 폴백**: `ANTHROPIC_API_KEY` 부재 시 claude delegate 자동 제외(부팅 실패 금지). 유키 시에만 체인 포함.
- **ThreadLocal 누수**: pool 스레드 재사용 대비 `providerName()` 읽은 뒤 `remove()`, `stream()` 진입 시 `remove()`.

## 6. 순서 / 롤아웃

1. FallbackMentorClient + 단위 테스트(Test-First).
2. MentorClientConfig 팩토리 + 배선 이관 + 설정 키(`fallback`) 추가.
3. 기존 테스트 green 유지 확인 + 로컬 실증(Ollama→mock 폴백).
4. develop PR. 운영 env(gitops `MENTOR_PROVIDER`/`MENTOR_FALLBACK`)는 AWS 재개 시 후속.

## 7. 영향 범위

| 레포 | 변경 |
|---|---|
| devpath-ai-svc | `mentor/FallbackMentorClient`(신규)·`mentor/MentorClientConfig`(신규)·3 client 배선 이관·`MentorClaudeClientConfig`(무키 안전)·`application.yml`(`devpath.mentor.fallback`)·테스트 |
| gitops | (후속) 운영 `MENTOR_PROVIDER`/`MENTOR_FALLBACK` env — 이 워크스트림 밖 |
