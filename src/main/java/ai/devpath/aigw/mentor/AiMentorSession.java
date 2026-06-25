package ai.devpath.aigw.mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_mentor_sessions")
public class AiMentorSession {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "user_id", nullable = false) private Long userId;
  @Column(name = "content_id") private Long contentId;
  @Column(nullable = false) private String question;
  @Column(nullable = false) private String answer = "";
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "context_snapshot", nullable = false) private String contextSnapshot = "{}";
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "reference_links", nullable = false) private String referenceLinks = "[]";
  private String provider;
  @Column(nullable = false) private String status;
  @Column(name = "error_code") private String errorCode;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void preUpdate() { updatedAt = Instant.now(); }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public void setUserId(Long v) { this.userId = v; }
  public Long getContentId() { return contentId; }
  public void setContentId(Long v) { this.contentId = v; }
  public String getQuestion() { return question; }
  public void setQuestion(String v) { this.question = v; }
  public String getAnswer() { return answer; }
  public void setAnswer(String v) { this.answer = v; }
  public String getContextSnapshot() { return contextSnapshot; }
  public void setContextSnapshot(String v) { this.contextSnapshot = v; }
  public String getReferenceLinks() { return referenceLinks; }
  public void setReferenceLinks(String v) { this.referenceLinks = v; }
  public String getProvider() { return provider; }
  public void setProvider(String v) { this.provider = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; }
  public String getErrorCode() { return errorCode; }
  public void setErrorCode(String v) { this.errorCode = v; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
