package ai.devpath.aigw.mentor;

/** learning-svc /internal/knowledge/similar 응답 항목. chunkText가 프롬프트 근거가 된다. */
public record KnowledgeChunk(
    String docKey, String title, String category, String chunkText, double distance) {}
