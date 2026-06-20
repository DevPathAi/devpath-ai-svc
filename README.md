# devpath-ai-svc

**DevPath AI** AI 서비스 — AI Gateway, 코드 리뷰 워커, FinOps를 담당합니다.

> **현재 구현 상태(2026-06-19)**: 로컬 개발 빌드는 Ollama gateway입니다. 실제 코드에는 `POST /ai/embed`, `POST /ai/path/generate`가 구현되어 있으며, 운영 목표는 Claude 등 외부 provider로 교체 가능한 AI Gateway입니다.

## 담당 도메인

| 모듈 | 역할 |
|------|------|
| ai-gateway | 현재 dev: Ollama embed/path 생성 위임 · 운영 목표: Claude 등 provider 단일 진입점 |
| review-worker | Kafka Consumer 기반 비동기 AI 코드 리뷰 (2nd Aha 핵심, 목표) |
| finops | 토큰 사용량/비용 집계 (목표) |

**아키텍처 원칙**: 모든 LLM 호출은 이 서비스를 경유합니다. 현재는 Ollama로 비용 없이 계약을 검증하고, 운영에서는 Claude API 등으로 교체합니다.

## 구성

- Spring Boot 4.0.x · Java 21 · Gradle (Kotlin DSL)
- [devpath-svc-template](https://github.com/DevPathAi/devpath-svc-template) 기반
- 패키지: `ai.devpath.aigw`
- 현재 구현 패키지: `ai.devpath.aigw.ollama`
- Kafka·Redis 의존성은 후속 review-worker/FinOps 구현 시 활성화

## 빌드 / 실행

```bash
./gradlew build
./gradlew bootRun    # 기본 포트 8080
```

로컬 Ollama 설정:

```bash
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_EMBED_MODEL=nomic-embed-text
OLLAMA_GEN_MODEL=qwen2.5:7b
```

로컬에 Ollama가 없다면 host에 설치하거나 Docker 기반 런타임을 사용합니다. 모델은 최초 1회 내려받습니다.

```bash
ollama pull nomic-embed-text
ollama pull qwen2.5:7b
```

운영 provider 키(`ANTHROPIC_API_KEY` 등)는 환경 변수로 주입하며 **절대 커밋하지 않습니다** ([documents/10_환경_설정_템플릿](https://github.com/DevPathAi/documents/blob/main/10_환경_설정_템플릿.md)).

## 개발 규칙

- Git 규칙: [documents/09_Git_규칙_정의서](https://github.com/DevPathAi/documents/blob/main/09_Git_규칙_정의서.md)
- 워크플로우 현황: `docs/project-management/` → [workflow-dashboard](https://devpathai.github.io/workflow-dashboard/)
