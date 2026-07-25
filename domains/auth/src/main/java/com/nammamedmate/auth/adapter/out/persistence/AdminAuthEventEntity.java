package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_auth_events")
public class AdminAuthEventEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "admin_id")
  private UUID adminId;

  @Column(name = "event_type", nullable = false, length = 40)
  private String eventType;

  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", nullable = false, columnDefinition = "inet")
  private String ipAddress;

  @Column(name = "user_agent")
  private String userAgent;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AdminAuthEventEntity() {}

  public AdminAuthEventEntity(
      UUID id,
      UUID adminId,
      String eventType,
      String ipAddress,
      String userAgent,
      Map<String, Object> metadata,
      Instant createdAt) {
    this.id = id;
    this.adminId = adminId;
    this.eventType = eventType;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.metadata = metadata;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAdminId() {
    return adminId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public Map<String, Object> getMetadata() {
    return metadata == null ? null : Map.copyOf(metadata);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
