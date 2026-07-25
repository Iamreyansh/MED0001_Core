package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customers")
public class CustomerEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "phone", nullable = false, length = 15, unique = true)
  private String phone;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "device_tokens", columnDefinition = "text[]")
  private String[] deviceTokens;

  @Column(name = "name")
  private String name;

  @Column(name = "avatar_url")
  private String avatarUrl;

  @Column(name = "date_of_birth")
  private LocalDate dateOfBirth;

  @Column(name = "gender")
  private String gender;

  @Column(name = "preferred_language")
  private String preferredLanguage;

  @Column(name = "segment")
  private String segment;

  @Column(name = "wallet_balance_paise", nullable = false)
  private long walletBalancePaise;

  @Column(name = "loyalty_points", nullable = false)
  private int loyaltyPoints;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected CustomerEntity() {}

  public CustomerEntity(
      UUID id,
      String phone,
      String[] deviceTokens,
      String name,
      String avatarUrl,
      LocalDate dateOfBirth,
      String gender,
      String preferredLanguage,
      String segment,
      long walletBalancePaise,
      int loyaltyPoints,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {
    this.id = id;
    this.phone = phone;
    this.deviceTokens = deviceTokens == null ? null : deviceTokens.clone();
    this.name = name;
    this.avatarUrl = avatarUrl;
    this.dateOfBirth = dateOfBirth;
    this.gender = gender;
    this.preferredLanguage = preferredLanguage;
    this.segment = segment;
    this.walletBalancePaise = walletBalancePaise;
    this.loyaltyPoints = loyaltyPoints;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.deletedAt = deletedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getPhone() {
    return phone;
  }

  public String[] getDeviceTokens() {
    return deviceTokens == null ? null : deviceTokens.clone();
  }

  public String getName() {
    return name;
  }

  public String getAvatarUrl() {
    return avatarUrl;
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public String getGender() {
    return gender;
  }

  public String getPreferredLanguage() {
    return preferredLanguage;
  }

  public String getSegment() {
    return segment;
  }

  public long getWalletBalancePaise() {
    return walletBalancePaise;
  }

  public int getLoyaltyPoints() {
    return loyaltyPoints;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }
}
