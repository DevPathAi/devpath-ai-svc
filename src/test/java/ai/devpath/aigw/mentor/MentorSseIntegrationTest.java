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
  @MockitoBean MentorSnapshotClient snapshotClient;

  @Test
  void legacyNullSnapshotStreamsTokensAndReferencesThenEofWithoutV2Terminal() throws Exception {
    when(sandboxClient.recentByUser(42L, 5)).thenReturn(List.of());
    when(ollamaClient.embed(List.of("비동기란?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));
    when(learningClient.searchSimilar(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(new SimilarContent(1, "a", "t")));
    doAnswer(inv -> {
      Consumer<String> sink = inv.getArgument(1);
      Consumer<String> provider = inv.getArgument(2);
      provider.accept("MOCK");
      sink.accept("비동기는 ");
      sink.accept("Future입니다.");
      return null;
    }).when(mentorClient).stream(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

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
    assertThat(body).doesNotContain("event:terminal", "event:error", "\"status\":");
    assertThat(repo.count()).isEqualTo(before + 1);
  }

  @Test
  void legacyNullSnapshotFailureUsesExistingErrorEventWithoutV2Terminal() throws Exception {
    when(sandboxClient.recentByUser(42L, 5)).thenReturn(List.of());
    when(ollamaClient.embed(List.of("비동기란?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));
    when(learningClient.searchSimilar(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    doAnswer(inv -> { throw new RuntimeException("llm down"); })
        .when(mentorClient).stream(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    MvcResult started = mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"비동기란?\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    String body = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(body).contains("event:error", "INTERNAL_ERROR", "mentor response unavailable");
    assertThat(body).doesNotContain("event:terminal", "AI_PROVIDER_UNAVAILABLE");
    assertThat(count(body, "event:error")).isEqualTo(1);
  }

  @Test
  void contextualSnapshotSuccessEmitsExactlyOneDoneTerminal() throws Exception {
    stubContextualRequest();
    doAnswer(inv -> {
      Consumer<String> provider = inv.getArgument(2);
      Consumer<String> sink = inv.getArgument(1);
      provider.accept("MOCK");
      sink.accept("contextual answer");
      return null;
    }).when(mentorClient).stream(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    String body = contextualRequest();

    assertThat(body).contains("event:terminal", "\"status\":\"DONE\"");
    assertThat(body).doesNotContain("event:error");
    assertThat(count(body, "event:terminal")).isEqualTo(1);
  }

  @Test
  void contextualSnapshotFailureEmitsExactlyOneFailedTerminal() throws Exception {
    stubContextualRequest();
    doAnswer(inv -> {
      Consumer<String> provider = inv.getArgument(2);
      provider.accept("CLAUDE");
      throw new RuntimeException("provider unavailable");
    }).when(mentorClient).stream(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    String body = contextualRequest();

    assertThat(body).contains("event:terminal", "\"status\":\"FAILED\"",
        "\"code\":\"AI_PROVIDER_UNAVAILABLE\"");
    assertThat(body).doesNotContain("event:error");
    assertThat(count(body, "event:terminal")).isEqualTo(1);
  }

  private void stubContextualRequest() {
    when(snapshotClient.consume(org.mockito.ArgumentMatchers.eq(23L),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(new MentorSnapshotContext(23L,
            "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\","
                + "\"fieldsIncluded\":[],\"content\":{}}",
            "{\"fieldsIncluded\":[],\"content\":{}}"));
    when(ollamaClient.embed(List.of("비동기란?")))
        .thenReturn(new EmbedResponse(List.of(Collections.nCopies(768, 0.1))));
    when(learningClient.searchSimilar(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
  }

  private String contextualRequest() throws Exception {
    MvcResult started = mvc.perform(post("/ai-mentor/sessions")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"비동기란?\",\"contextSnapshotId\":23}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    return mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  private static int count(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
  }
}
