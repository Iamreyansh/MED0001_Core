package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record SaasSubscription(
    UUID id,
    UUID accountId,
    UUID planId,
    UUID scheduledPlanId,
    String status,
    String billingCycle,
    Instant renewalDate,
    Instant trialEndsAt,
    boolean autoRenew,
    Instant cancelledAt,
    Instant cancelsAt,
    Instant expiresAt,
    Instant pastDueAt,
    UUID lastInvoiceId,
    UUID overridePlanId,
    Instant overrideExpiresAt,
    String overrideReason,
    Instant createdAt,
    Instant updatedAt) {

  public boolean overrideActive(Instant now) {
    return overridePlanId != null && overrideExpiresAt != null && overrideExpiresAt.isAfter(now);
  }
}
