package ai.devpath.aigw.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCodeReviewRepository extends JpaRepository<AiCodeReview, Long> {
  Optional<AiCodeReview> findBySandboxSessionId(long sandboxSessionId);
  boolean existsBySandboxSessionId(long sandboxSessionId);
}
