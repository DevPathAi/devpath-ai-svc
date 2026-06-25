package ai.devpath.aigw.mentor;

/** 멘토 맥락: promptText=LLM 주입용, snapshotJson=영속용 요약, track=참고자료 검색용(없으면 null). */
public record MentorContext(String promptText, String snapshotJson, String track) {}
