package ai.devpath.aigw.release;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.aigw.config.InternalApiAuthenticationFilter;
import ai.devpath.aigw.config.SecurityConfig;
import ai.devpath.aigw.review.ReviewPersistenceService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AiReleaseController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "devpath.release.enabled=true",
    "devpath.auth.internal-token=release-internal-token"
})
class AiReleaseSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean ReleaseJourneyRegistry release;
  @MockitoBean ReviewPersistenceService persistence;

  @Test
  void internalReleaseCommandsRequireTheExistingWorkloadCredential() throws Exception {
    String candidate = "a".repeat(64);
    String runKey = "R".repeat(43);
    String path = "/internal/release/ai/" + candidate + "/" + runKey
        + "/commands/clear-faults";
    when(release.clear(candidate, runKey, 42L)).thenReturn(Optional.empty());

    mvc.perform(post(path).contentType("application/json").content("{\"user_id\":42}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(path)
            .header(InternalApiAuthenticationFilter.HEADER, "release-internal-token")
            .contentType("application/json")
            .content("{\"user_id\":42}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));
  }
}
