package com.nammamedmate.teleconsult.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** In-house teleconsult roster row (distinct from EPIC-008 prescribing doctor registry). */
public record TeleconsultDoctor(
    UUID id,
    String name,
    String qualification,
    String registrationNo,
    String specialty,
    List<String> languagesSpoken,
    int yearsExperience,
    String avatarUrl,
    String bio,
    String internalPhoneCiphertext,
    boolean available,
    BigDecimal avgRating,
    int totalConsults,
    int consultsToday,
    Instant lastAssignedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public TeleconsultDoctor {
    languagesSpoken = languagesSpoken == null ? List.of() : List.copyOf(languagesSpoken);
  }

  public boolean profileComplete() {
    return avatarUrl != null && !avatarUrl.isBlank() && bio != null && !bio.isBlank();
  }
}
