package com.nammamedmate.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

@MappedSuperclass
public abstract class SoftDeletable {

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void softDelete(Instant when) {
    this.deletedAt = when;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
