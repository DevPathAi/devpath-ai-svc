package ai.devpath.aigw.mentor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MentorControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean MentorSnapshotClient snapshotClient;

  @Test
  void streamsSseForAuthenticatedUser() throws Exception {
    MvcResult mvcResult = mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"비동기란?\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
  }

  @Test
  void rejectsEmptyMessage() throws Exception {
    mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(post("/ai-mentor/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"q\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void snapshotDenialIsGeneric404BeforeSseStarts() throws Exception {
    when(snapshotClient.consume(23L, "token"))
        .thenThrow(new MentorSnapshotUnavailableException());

    mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"질문\",\"contentId\":7,\"contextSnapshotId\":23}"))
        .andExpect(status().isNotFound())
        .andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value("mentor snapshot unavailable"));
  }

  @Test
  void snapshotServiceFailureIsRecoverable503BeforeSseStarts() throws Exception {
    when(snapshotClient.consume(24L, "token"))
        .thenThrow(new MentorSnapshotServiceUnavailableException());

    mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"질문\",\"contextSnapshotId\":24}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.error.code").value("STORAGE_UNAVAILABLE"))
        .andExpect(jsonPath("$.error.message")
            .value("mentor snapshot temporarily unavailable"));
  }
}
