## Step 1: #3 학습경로 (1st Aha)
### 1.1 AI Gateway 경로 생성

> **상태(2026-06-21)**: Claude 직접 연동 계획은 로컬 Ollama gateway로 대체되어 구현됨. `POST /ai/embed`, `POST /ai/path/generate`와 Ollama 계약/오류 매핑은 MockWebServer 테스트로 검증한다. 학습경로 영속화, SSE 스트리밍, `LearningPathGeneratedEvent`는 아직 learning-svc 후속 범위다.

- [x] Ollama API 클라이언트(dev: `nomic-embed-text`, `qwen2.5:7b`)
- [x] Path 생성 JSON strict 파싱
- [x] Ollama embed/path 계약, 재시도, 오류 매핑 테스트
- [ ] 운영 provider(Claude 등) 어댑터
- [ ] SSE 스트리밍(4단계) + LearningPathGeneratedEvent
