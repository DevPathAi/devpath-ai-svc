package ai.devpath.aigw.retention;

/** 재참여 문구 생성 추상화. provider 프로퍼티(devpath.retention.provider)로 구현 선택. */
public interface ReEngagementSuggestionClient {
	String suggest(ReEngagementInput input);
	String providerName();
}
