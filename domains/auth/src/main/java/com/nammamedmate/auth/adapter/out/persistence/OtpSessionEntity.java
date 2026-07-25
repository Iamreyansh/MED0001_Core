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
@Table(name = "otp_sessions")
public class OtpSessionEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "phone", nullable = false, length = 15)
  private String phone;

  @Column(name = "otp_hash", nullable = false, length = 60)
  private String otpHash;

  @Column(name = "attempts", nullable = false)
  private short attempts;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "device_info", columnDefinition = "jsonb")
  private String deviceInfoJson;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected OtpSessionEntity() {}

  public OtpSessionEntity(
      UUID id,
      String phone,
      String otpHash,
      short attempts,
      String deviceInfoJson,
      Instant expiresAt,
      Instant verifiedAt,
      Instant lockedAt,
      Instant createdAt) {
    this.id = id;
    this.phone = phone;
    this.otpHash = otpHash;
    this.attempts = attempts;
    this.deviceInfoJson = deviceInfoJson;
    this.expiresAt = expiresAt;
    this.verifiedAt = verifiedAt;
    this.lockedAt = lockedAt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getPhone() {
    return phone;
  }

  public String getOtpHash() {
    return otpHash;
  }

  public short getAttempts() {
    return attempts;
  }

  public String getDeviceInfoJson() {
    return deviceInfoJson;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public Instant getLockedAt() {
    return lockedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
