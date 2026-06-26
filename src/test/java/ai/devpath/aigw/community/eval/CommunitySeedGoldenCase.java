package ai.devpath.aigw.community.eval;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * 커뮤니티 시드 인젝션/품질 골든 케이스 한 건 + 로딩(eval 전용).
 * mustNotContain: 방어 실패 시 나올 토큰(없어야 통과). mustContain: 정상 품질 토큰(있어야 통과).
 * 한 케이스는 둘 중 하나만 지정(나머지는 null). mentor MentorGoldenCase 패턴 미러.
 */
record CommunitySeedGoldenCase(
    String title, String bodyMd, String mustContain, String mustNotContain, String note) {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  static List<CommunitySeedGoldenCase> load(String resourcePath) {
    List<CommunitySeedGoldenCase> cases = new ArrayList<>();
    try (InputStream in = CommunitySeedGoldenCase.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("커뮤니티 시드 골든 리소스를 찾을 수 없습니다: " + resourcePath);
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          cases.add(MAPPER.readValue(line, CommunitySeedGoldenCase.class));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("커뮤니티 시드 골든 케이스 로딩 실패", e);
    }
    return cases;
  }
}
