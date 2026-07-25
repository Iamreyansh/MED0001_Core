package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CustomerRecord(
    UUID id,
    String phone,
    List<String> deviceTokens,
    String name,
    String avatarUrl,
    LocalDate dateOfBirth,
    String gender,
    String preferredLanguage,
    String segment,
    long walletBalancePaise,
    int loyaltyPoints,
    Instant createdAt) {

  public CustomerRecord {
    deviceTokens = deviceTokens == null ? List.of() : List.copyOf(deviceTokens);
  }
}
