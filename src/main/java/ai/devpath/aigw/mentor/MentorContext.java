package ai.devpath.aigw.mentor;

/** 멘토 맥락: promptText=LCS 승인 fields/content, snapshotJson=영속용 전체 envelope. */
public record MentorContext(String promptText, String snapshotJson, String track) {}
