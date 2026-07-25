package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pharmacies")
public class PharmacyEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "logo_url", length = 1024)
  private String logoUrl;

  @Column(name = "city", length = 100)
  private String city;

  @Column(name = "subscription_plan", nullable = false, length = 32)
  private String subscriptionPlan;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected PharmacyEntity() {}

  public PharmacyEntity(
      UUID id,
      String name,
      String logoUrl,
      String city,
      String subscriptionPlan,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.name = name;
    this.logoUrl = logoUrl;
    this.city = city;
    this.subscriptionPlan = subscriptionPlan;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getLogoUrl() {
    return logoUrl;
  }

  public String getCity() {
    return city;
  }

  public String getSubscriptionPlan() {
    return subscriptionPlan;
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
