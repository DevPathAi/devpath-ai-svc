# devpath-ai-svc

**DevPath AI** AI 서비스 — Claude API 오케스트레이션, 코드 리뷰 워커, FinOps를 담당합니다.

## 담당 도메인

| 모듈 | 역할 |
|------|------|
| ai-gateway | Claude API 단일 진입점 — 비용 추적, Semantic Cache, Kill-switch |
| review-worker | Kafka Consumer 기반 비동기 AI 코드 리뷰 (2nd Aha 핵심) |
| finops | 토큰 사용량/비용 집계 |

**아키텍처 원칙**: 모든 Claude API 호출은 이 서비스를 경유합니다 (비용·캐시·차단 일원화).

## 구성

- Spring Boot 4.0.x · Java 21 · Gradle (Kotlin DSL)
- [devpath-svc-template](https://github.com/DevPathAi/devpath-svc-template) 기반
- 패키지: `ai.devpath.aigw`
- Kafka·Redis 의존성은 `build.gradle.kts` 주석 해제로 활성화

## 빌드 / 실행

```bash
./gradlew build
./gradlew bootRun    # 기본 포트 8080
```

`ANTHROPIC_API_KEY`는 환경 변수로 주입하며 **절대 커밋하지 않습니다** ([documents/10_환경_설정_템플릿](https://github.com/DevPathAi/documents/blob/main/10_환경_설정_템플릿.md)).

## 개발 규칙

- Git 규칙: [documents/09_Git_규칙_정의서](https://github.com/DevPathAi/documents/blob/main/09_Git_규칙_정의서.md)
- 워크플로우 현황: `docs/project-management/` → [workflow-dashboard](https://devpathai.github.io/workflow-dashboard/)
