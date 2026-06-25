package ai.devpath.aigw.mentor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MentorControllerTest {

  @Autowired MockMvc mvc;

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
        .andExpect(status().isBadRequest());
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(post("/ai-mentor/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"q\"}"))
        .andExpect(status().isUnauthorized());
  }
}
