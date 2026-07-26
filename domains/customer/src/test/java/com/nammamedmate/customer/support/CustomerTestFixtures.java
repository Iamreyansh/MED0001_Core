package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class CustomerTestFixtures {

  public static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");

  private CustomerTestFixtures() {}

  public static CustomerProfileRecord customer(UUID id) {
    return new CustomerProfileRecord(
        id,
        "+919876543210",
        "Test User",
        "https://cdn.namma-medmate.in/avatars/abc.png",
        LocalDate.of(1990, 1, 15),
        "MALE",
        "en",
        "REGULAR",
        "Bengaluru",
        false,
        null,
        null,
        null,
        null,
        12_500L,
        75,
        5,
        250_000L,
        new BigDecimal("0.10"),
        0,
        NOW.minusSeconds(3600),
        null,
        null,
        NOW.minusSeconds(86_400),
        NOW,
        null);
  }

  public static CustomerProfileRecord customerWithName(UUID id, String name) {
    CustomerProfileRecord base = customer(id);
    return new CustomerProfileRecord(
        base.id(),
        base.phone(),
        name,
        base.avatarUrl(),
        base.dateOfBirth(),
        base.gender(),
        base.preferredLanguage(),
        base.segment(),
        base.city(),
        base.isFlagged(),
        base.flagReason(),
        base.flagNote(),
        base.flaggedBy(),
        base.flaggedAt(),
        base.walletBalancePaise(),
        base.loyaltyPoints(),
        base.totalOrders(),
        base.totalLtvPaise(),
        base.cancelRate(),
        base.disputeCount(),
        base.lastOrderAt(),
        base.deletionRequestedAt(),
        base.deletionReason(),
        base.createdAt(),
        base.updatedAt(),
        base.deletedAt());
  }

  public static CustomerProfileRecord customerWith(
      UUID id, String segment, int totalOrders, long totalLtvPaise, boolean flagged) {
    CustomerProfileRecord base = customer(id);
    return new CustomerProfileRecord(
        base.id(),
        base.phone(),
        base.name(),
        base.avatarUrl(),
        base.dateOfBirth(),
        base.gender(),
        base.preferredLanguage(),
        segment,
        base.city(),
        flagged,
        flagged ? "FRAUD_SUSPICION" : null,
        flagged ? "note" : null,
        flagged ? UUID.randomUUID() : null,
        flagged ? NOW : null,
        base.walletBalancePaise(),
        base.loyaltyPoints(),
        totalOrders,
        totalLtvPaise,
        base.cancelRate(),
        base.disputeCount(),
        base.lastOrderAt(),
        base.deletionRequestedAt(),
        base.deletionReason(),
        base.createdAt(),
        base.updatedAt(),
        base.deletedAt());
  }
}
