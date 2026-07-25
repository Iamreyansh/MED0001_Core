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
@Table(name = "sessions")
public class AuthSessionEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "user_type", nullable = false, length = 20)
  private String userType;

  @Column(name = "pharmacy_id")
  private UUID pharmacyId;

  @Column(name = "refresh_token_hash", nullable = false, length = 64, unique = true)
  private String refreshTokenHash;

  @Column(name = "token_scope", nullable = false, length = 20)
  private String tokenScope;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "device_info", columnDefinition = "jsonb")
  private String deviceInfoJson;

  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", nullable = false, columnDefinition = "inet")
  private String ipAddress;

  @Column(name = "user_agent")
  private String userAgent;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_active_at", nullable = false)
  private Instant lastActiveAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected AuthSessionEntity() {}

  public AuthSessionEntity(
      UUID id,
      UUID userId,
      String userType,
      String refreshTokenHash,
      String tokenScope,
      String deviceInfoJson,
      String ipAddress,
      String userAgent,
      Instant createdAt,
      Instant lastActiveAt,
      Instant expiresAt,
      UUID pharmacyId) {
    this.id = id;
    this.userId = userId;
    this.userType = userType;
    this.pharmacyId = pharmacyId;
    this.refreshTokenHash = refreshTokenHash;
    this.tokenScope = tokenScope;
    this.deviceInfoJson = deviceInfoJson;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.createdAt = createdAt;
    this.lastActiveAt = lastActiveAt;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getUserType() {
    return userType;
  }

  public UUID getPharmacyId() {
    return pharmacyId;
  }

  public String getRefreshTokenHash() {
    return refreshTokenHash;
  }

  public String getTokenScope() {
    return tokenScope;
  }

  public String getDeviceInfoJson() {
    return deviceInfoJson;
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

  public Instant getLastActiveAt() {
    return lastActiveAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
