package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.aigw.ollama.OllamaClient;
import ai.devpath.aigw.ollama.dto.EmbedResponse;
import ai.devpath.aigw.review.SandboxClient;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
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
class MentorSseIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired AiMentorSessionRepository repo;
  @MockitoBean SandboxClient sandboxClient;
  @MockitoBean LearningClient learningClient;
  @MockitoBean OllamaClient ollamaClient;
  @MockitoBean AiMentorClient mentorClient;

  @Test
  void endToEndMockStreamsTokensAndPersistsDone() throws Exception {
    when(sandboxClient.recentByUser(42L, 5)).thenReturn(List.of());
    when(ollamaClient.embed(List.of("비동기란?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));
    when(learningClient.searchSimilar(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(new SimilarContent(1, "a", "t")));
    when(mentorClient.providerName()).thenReturn("MOCK");
    doAnswer(inv -> {
      Consumer<String> sink = inv.getArgument(1);
      sink.accept("비동기는 ");
      sink.accept("Future입니다.");
      return null;
    }).when(mentorClient).stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    long before = repo.count();
    MvcResult started = mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"비동기란?\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    String body = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(body).contains("token");
    assertThat(body).contains("references");
    assertThat(repo.count()).isEqualTo(before + 1);
  }

  @Test
  void llmFailureMidStreamEmitsErrorEventWithEnvelopeAndCompletes() throws Exception {
    when(sandboxClient.recentByUser(42L, 5)).thenReturn(List.of());
    when(ollamaClient.embed(List.of("비동기란?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));
    when(learningClient.searchSimilar(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    doAnswer(inv -> { throw new RuntimeException("llm down"); })
        .when(mentorClient).stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    MvcResult started = mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"비동기란?\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    String body = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(body).contains("event:error");
    assertThat(body).contains("\"code\":\"INTERNAL_ERROR\"");
  }
}
