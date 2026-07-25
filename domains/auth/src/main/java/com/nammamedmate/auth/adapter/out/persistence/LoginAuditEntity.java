package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "auth_login_audit")
public class LoginAuditEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "actor_type", nullable = false, length = 32)
  private String actorType;

  @Column(name = "identifier", length = 255)
  private String identifier;

  @Column(name = "staff_id")
  private UUID staffId;

  @Column(name = "success", nullable = false)
  private boolean success;

  @Column(name = "failure_reason", length = 64)
  private String failureReason;

  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", columnDefinition = "inet")
  private String ipAddress;

  @Column(name = "user_agent")
  private String userAgent;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected LoginAuditEntity() {}

  public LoginAuditEntity(
      UUID id,
      String actorType,
      String identifier,
      UUID staffId,
      boolean success,
      String failureReason,
      String ipAddress,
      String userAgent,
      Instant createdAt) {
    this.id = id;
    this.actorType = actorType;
    this.identifier = identifier;
    this.staffId = staffId;
    this.success = success;
    this.failureReason = failureReason;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getActorType() {
    return actorType;
  }

  public String getIdentifier() {
    return identifier;
  }

  public UUID getStaffId() {
    return staffId;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
