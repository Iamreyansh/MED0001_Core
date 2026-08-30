package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record Distributor(
    UUID id,
    UUID pharmacyId,
    String firmName,
    String contactName,
    String phone,
    String email,
    String gstin,
    String drugLicenceNumber,
    String address,
    int paymentTermsDays,
    long creditLimitPaise,
    boolean active,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public static final String WALK_IN_FIRM = "Cash / Walk-in";
  public static final String WALK_IN_PHONE = "+919000000000";

  /** Minimal constructor for GRN FK seeding (STORY-004). */
  public static Distributor minimal(
      UUID id, UUID pharmacyId, String firmName, boolean active, Instant now) {
    return new Distributor(
        id,
        pharmacyId,
        firmName,
        null,
        "+910000000000",
        null,
        null,
        null,
        null,
        0,
        0L,
        active,
        now,
        now,
        null);
  }

  /** System supplier used when Free GRN omits distributor_id. */
  public static Distributor walkIn(UUID id, UUID pharmacyId, Instant now) {
    return new Distributor(
        id,
        pharmacyId,
        WALK_IN_FIRM,
        null,
        WALK_IN_PHONE,
        null,
        null,
        null,
        null,
        0,
        0L,
        true,
        now,
        now,
        null);
  }

  public Distributor withActive(boolean active) {
    return new Distributor(
        id,
        pharmacyId,
        firmName,
        contactName,
        phone,
        email,
        gstin,
        drugLicenceNumber,
        address,
        paymentTermsDays,
        creditLimitPaise,
        active,
        createdAt,
        updatedAt,
        deletedAt);
  }

  public Distributor withDeletedAt(Instant deletedAt) {
    return new Distributor(
        id,
        pharmacyId,
        firmName,
        contactName,
        phone,
        email,
        gstin,
        drugLicenceNumber,
        address,
        paymentTermsDays,
        creditLimitPaise,
        active,
        createdAt,
        updatedAt,
        deletedAt);
  }

  public boolean usable() {
    return active && deletedAt == null;
  }

  public boolean onCredit() {
    return paymentTermsDays > 0;
  }
}
