package ai.devpath.aigw.review;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewControllerTest {

  @Autowired MockMvc mvc;
  @Autowired AiCodeReviewRepository reviews;

  private long seed(long userId, String status) {
    AiCodeReview r = new AiCodeReview();
    r.setSandboxSessionId(System.nanoTime());
    r.setUserId(userId);
    r.setStatus(status);
    r.setConfidence(80);
    r.setStrengths("[\"good\"]");
    return reviews.save(r).getId();
  }

  @Test
  void getByIdReturnsReviewForOwner() throws Exception {
    long id = seed(42L, "DONE");
    mvc.perform(get("/reviews/" + id).with(jwt().jwt(j -> j.subject("42"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.confidence").value(80))
        .andExpect(jsonPath("$.strengths[0]").value("good"));
    reviews.deleteById(id);
  }

  @Test
  void getByIdForbiddenForNonOwner() throws Exception {
    long id = seed(42L, "DONE");
    mvc.perform(get("/reviews/" + id).with(jwt().jwt(j -> j.subject("999"))))
        .andExpect(status().isForbidden());
    reviews.deleteById(id);
  }

  @Test
  void bySandboxSessionReturnsPendingStatus() throws Exception {
    AiCodeReview r = new AiCodeReview();
    long sid = System.nanoTime();
    r.setSandboxSessionId(sid); r.setUserId(42L); r.setStatus("PENDING");
    reviews.save(r);

    mvc.perform(get("/reviews").param("sandboxSessionId", String.valueOf(sid))
            .with(jwt().jwt(j -> j.subject("42"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
    reviews.deleteById(r.getId());
  }

  @Test
  void feedbackPersistsUpDown() throws Exception {
    long id = seed(42L, "DONE");
    mvc.perform(post("/reviews/" + id + "/feedback")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"UP\"}"))
        .andExpect(status().isOk());
    org.assertj.core.api.Assertions.assertThat(reviews.findById(id).orElseThrow().getFeedback())
        .isEqualTo("UP");
    reviews.deleteById(id);
  }

  @Test
  void missingReviewReturns404() throws Exception {
    mvc.perform(get("/reviews/999999999").with(jwt().jwt(j -> j.subject("42"))))
        .andExpect(status().isNotFound());
  }
}
