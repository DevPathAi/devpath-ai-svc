package ai.devpath.aigw.review;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/reviews")
public class ReviewController {

  private final AiCodeReviewRepository reviews;
  private final JsonMapper jsonMapper;

  public ReviewController(AiCodeReviewRepository reviews, JsonMapper jsonMapper) {
    this.reviews = reviews;
    this.jsonMapper = jsonMapper;
  }

  @GetMapping(params = "sandboxSessionId")
  public CodeReview bySandboxSession(@AuthenticationPrincipal Jwt jwt,
      @RequestParam long sandboxSessionId) {
    long userId = Long.parseLong(jwt.getSubject());
    AiCodeReview r = reviews.findBySandboxSessionId(sandboxSessionId)
        .orElseThrow(() -> new ReviewNotFoundException("review not found for session " + sandboxSessionId));
    requireOwner(r, userId);
    return toDto(r);
  }

  @GetMapping("/{id}")
  public CodeReview byId(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
    long userId = Long.parseLong(jwt.getSubject());
    AiCodeReview r = reviews.findById(id)
        .orElseThrow(() -> new ReviewNotFoundException("review not found: " + id));
    requireOwner(r, userId);
    return toDto(r);
  }

  @PostMapping("/{id}/feedback")
  public CodeReview feedback(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
      @RequestBody FeedbackRequest req) {
    long userId = Long.parseLong(jwt.getSubject());
    AiCodeReview r = reviews.findById(id)
        .orElseThrow(() -> new ReviewNotFoundException("review not found: " + id));
    requireOwner(r, userId);
    if (req == null || (!"UP".equals(req.value()) && !"DOWN".equals(req.value()))) {
      throw new IllegalArgumentException("feedback value must be UP or DOWN");
    }
    r.setFeedback(req.value());
    reviews.save(r);
    return toDto(r);
  }

  private static void requireOwner(AiCodeReview r, long userId) {
    if (r.getUserId() == null || r.getUserId() != userId) {
      throw new AccessDeniedException("not the review owner");
    }
  }

  private CodeReview toDto(AiCodeReview r) {
    return new CodeReview(
        String.valueOf(r.getId()),
        r.getStatus(),
        r.getConfidence() == null ? 0 : r.getConfidence(),
        readList(r.getStrengths(), String.class),
        readList(r.getImprovements(), ReviewIssue.class),
        readList(r.getSecurity(), ReviewIssue.class));
  }

  private <T> List<T> readList(String json, Class<T> type) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return jsonMapper.readValue(json,
          jsonMapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (Exception e) {
      return List.of();
    }
  }

  public record FeedbackRequest(String value) {}
}
