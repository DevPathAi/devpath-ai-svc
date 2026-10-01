package ai.devpath.aigw.provider;

/**
 * provider 실패의 종류. <b>가용성 실패만 래치를 연다</b> — 내용 실패({@link #BAD_REQUEST},
 * {@link #OUTPUT_INVALID})는 그 요청만 다음 provider 로 넘기고 래치를 건드리지 않는다.
 * 한 사용자의 잘못된 프롬프트가 전체의 Claude 를 끊으면 안 된다.
 */
public enum FailureKind {
  /** 401·403. 키가 틀렸거나 회수됐다 — 재시도가 의미 없다. */
  AUTH,
  /** 429. 소진과 단기 제한이 같은 코드로 온다. */
  RATE_LIMIT,
  /** 5xx·연결·타임아웃. 일시적일 수 있다 — 한 번으로 끊지 않는다. */
  TRANSIENT,
  /** 400. 우리 버그다. 래치를 열지 않는다. */
  BAD_REQUEST,
  /** 파싱·스키마 실패. 가용성 문제가 아니다. 래치를 열지 않는다. */
  OUTPUT_INVALID
}
