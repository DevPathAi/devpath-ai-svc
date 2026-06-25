package ai.devpath.aigw.mentor.eval;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * 멘토 인젝션/품질 골든 케이스 한 건 + 로딩(eval 전용).
 * mustNotContain: 방어 실패 시 나올 토큰(없어야 통과). mustContain: 정상 품질 토큰(있어야 통과).
 * 한 케이스는 둘 중 하나만 지정(나머지는 null). #6 GoldenCase 패턴 미러.
 */
record MentorGoldenCase(
    String question, String context, String mustContain, String mustNotContain, String note) {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  static List<MentorGoldenCase> load(String resourcePath) {
    List<MentorGoldenCase> cases = new ArrayList<>();
    try (InputStream in = MentorGoldenCase.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("멘토 골든 리소스를 찾을 수 없습니다: " + resourcePath);
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          cases.add(MAPPER.readValue(line, MentorGoldenCase.class));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("멘토 골든 케이스 로딩 실패", e);
    }
    return cases;
  }
}
