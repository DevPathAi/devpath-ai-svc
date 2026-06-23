package ai.devpath.aigw.review;

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
@Table(name = "ai_code_reviews")
public class AiCodeReview {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "sandbox_session_id", nullable = false) private Long sandboxSessionId;
  @Column(name = "user_id", nullable = false) private Long userId;
  @Column(name = "content_id") private Long contentId;
  @Column(nullable = false) private String status = "PENDING";
  private String provider;
  private Integer confidence;
  @JdbcTypeCode(SqlTypes.JSON) private String strengths = "[]";
  @JdbcTypeCode(SqlTypes.JSON) private String improvements = "[]";
  @JdbcTypeCode(SqlTypes.JSON) private String security = "[]";
  private String feedback;
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
  public Long getSandboxSessionId() { return sandboxSessionId; }
  public void setSandboxSessionId(Long v) { this.sandboxSessionId = v; }
  public Long getUserId() { return userId; }
  public void setUserId(Long v) { this.userId = v; }
  public Long getContentId() { return contentId; }
  public void setContentId(Long v) { this.contentId = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; }
  public String getProvider() { return provider; }
  public void setProvider(String v) { this.provider = v; }
  public Integer getConfidence() { return confidence; }
  public void setConfidence(Integer v) { this.confidence = v; }
  public String getStrengths() { return strengths; }
  public void setStrengths(String v) { this.strengths = v; }
  public String getImprovements() { return improvements; }
  public void setImprovements(String v) { this.improvements = v; }
  public String getSecurity() { return security; }
  public void setSecurity(String v) { this.security = v; }
  public String getFeedback() { return feedback; }
  public void setFeedback(String v) { this.feedback = v; }
  public String getErrorCode() { return errorCode; }
  public void setErrorCode(String v) { this.errorCode = v; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
