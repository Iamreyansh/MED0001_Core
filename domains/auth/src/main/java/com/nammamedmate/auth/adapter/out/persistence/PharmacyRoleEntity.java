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
@Table(name = "pharmacy_roles")
public class PharmacyRoleEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "pharmacy_id")
  private UUID pharmacyId;

  @Column(name = "code", nullable = false, length = 64)
  private String code;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "display_name", nullable = false, length = 100)
  private String displayName;

  @Column(name = "is_system", nullable = false)
  private boolean system;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "permissions", nullable = false, columnDefinition = "text[]")
  private String[] permissions;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected PharmacyRoleEntity() {}

  public PharmacyRoleEntity(
      UUID id,
      UUID pharmacyId,
      String code,
      String name,
      String displayName,
      boolean system,
      String[] permissions,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {
    this.id = id;
    this.pharmacyId = pharmacyId;
    this.code = code;
    this.name = name;
    this.displayName = displayName;
    this.system = system;
    this.permissions = permissions;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.deletedAt = deletedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPharmacyId() {
    return pharmacyId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getDisplayName() {
    return displayName;
  }

  public boolean isSystem() {
    return system;
  }

  public String[] getPermissions() {
    return permissions == null ? null : permissions.clone();
  }

  public void setPermissions(String[] permissions) {
    this.permissions = permissions == null ? null : permissions.clone();
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }
}
