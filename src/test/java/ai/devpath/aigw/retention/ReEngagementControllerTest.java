package ai.devpath.aigw.retention;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"devpath.retention.provider=mock", "devpath.retention.enabled=true"})
class ReEngagementControllerTest {

  @Autowired MockMvc mvc;

  @Test
  void returnsNonEmptyMessageForStagnatedUser() throws Exception {
    mvc.perform(post("/ai/re-engagement")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":42,\"lastActiveAt\":\"2026-06-30T00:00:00Z\",\"daysInactive\":3,\"currentLearningPathSummary\":\"백엔드 스프링 트랙 (12주 과정)\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  @Test
  void handlesNullSummary() throws Exception {
    mvc.perform(post("/ai/re-engagement")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":42,\"lastActiveAt\":\"2026-06-30T00:00:00Z\",\"daysInactive\":3,\"currentLearningPathSummary\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").isNotEmpty());
  }
}
