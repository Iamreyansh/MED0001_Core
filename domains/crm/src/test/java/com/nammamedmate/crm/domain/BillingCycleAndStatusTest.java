package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BillingCycleAndStatusTest {

  @Test
  void advanceAndPrice() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    assertThat(BillingCycle.advance(from, BillingCycle.MONTHLY)).isAfter(from);
    assertThat(BillingCycle.advance(from, BillingCycle.ANNUAL)).isAfter(from);
    assertThat(BillingCycle.cyclePricePaise(100, BillingCycle.ANNUAL)).isEqualTo(1000L);
    assertThat(BillingCycle.cyclePricePaise(100, BillingCycle.MONTHLY)).isEqualTo(100L);
    assertThat(BillingCycle.requireValid(" annual ")).isEqualTo(BillingCycle.ANNUAL);
    assertThat(BillingCycle.requireValid("")).isEqualTo(BillingCycle.MONTHLY);
  }

  @Test
  void statusAccess() {
    assertThat(SubscriptionStatus.hasModuleAccess(SubscriptionStatus.ACTIVE)).isTrue();
    assertThat(SubscriptionStatus.hasModuleAccess(SubscriptionStatus.TRIAL)).isTrue();
    assertThat(SubscriptionStatus.hasModuleAccess(SubscriptionStatus.PAST_DUE)).isTrue();
    assertThat(SubscriptionStatus.hasModuleAccess(SubscriptionStatus.EXPIRED)).isFalse();
    assertThat(SubscriptionStatus.hasModuleAccess(SubscriptionStatus.CANCELLED)).isFalse();
  }

  @Test
  void overrideActiveBranches() {
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    SaasSubscription none =
        new SaasSubscription(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            null,
            "ACTIVE",
            "MONTHLY",
            now,
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    assertThat(none.overrideActive(now)).isFalse();
    SaasSubscription expired =
        new SaasSubscription(
            none.id(),
            none.accountId(),
            none.planId(),
            null,
            "ACTIVE",
            "MONTHLY",
            now,
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            java.util.UUID.randomUUID(),
            now.minusSeconds(10),
            "r",
            now,
            now);
    assertThat(expired.overrideActive(now)).isFalse();
    SaasSubscription active =
        new SaasSubscription(
            none.id(),
            none.accountId(),
            none.planId(),
            null,
            "ACTIVE",
            "MONTHLY",
            now,
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            java.util.UUID.randomUUID(),
            now.plusSeconds(10),
            "r",
            now,
            now);
    SaasSubscription noExpiry =
        new SaasSubscription(
            none.id(),
            none.accountId(),
            none.planId(),
            null,
            "ACTIVE",
            "MONTHLY",
            now,
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            java.util.UUID.randomUUID(),
            null,
            "r",
            now,
            now);
    assertThat(noExpiry.overrideActive(now)).isFalse();
  }
}
