package ai.devpath.aigw.mentor;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MentorBusyHttpTest {

  @Autowired MockMvc mvc;
  @MockitoBean MentorExecutionCoordinator coordinator;
  @MockitoBean MentorPersistenceService persistence;
  @MockitoBean AiMentorClient provider;

  @Test
  void saturatedAdmissionReturnsDedicatedRetryable429BeforeSseOrSideEffects() throws Exception {
    when(coordinator.start(42L, "질문", null, null)).thenThrow(new MentorBusyException());

    mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(token -> token.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"질문\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.error.code").value("MENTOR_BUSY"))
        .andExpect(jsonPath("$.error.message").value("mentor is busy; retry later"));

    verifyNoInteractions(persistence, provider);
  }
}
