package com.nammamedmate.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BasePayAndPayoutDomainTest {

  @Test
  void ac001_basePayInterpolation() {
    assertThat(BasePayFormula.computePaise(new BigDecimal("2.0"))).isEqualTo(1500L);
    assertThat(BasePayFormula.computePaise(new BigDecimal("1.0"))).isEqualTo(1500L);
    assertThat(BasePayFormula.computePaise(new BigDecimal("5.0"))).isEqualTo(2500L);
    assertThat(BasePayFormula.computePaise(new BigDecimal("6.0"))).isEqualTo(2500L);
    assertThat(BasePayFormula.computePaise(new BigDecimal("3.5"))).isEqualTo(2000L);
    assertThat(BasePayFormula.computePaise(null)).isEqualTo(1500L);
  }

  @Test
  void ac001_configurableRates() {
    long custom =
        BasePayFormula.computePaise(
            new BigDecimal("3.5"), 1000L, 3000L, new BigDecimal("2.0"), new BigDecimal("5.0"));
    assertThat(custom).isEqualTo(2000L);
    assertThat(
            BasePayFormula.computePaise(
                new BigDecimal("3.0"), cfg("rider_base_pay_min_paise", "1500")))
        .isEqualTo(1833L);
  }

  @Test
  void payoutCycleMonSunIst() {
    // Friday 2026-07-24 09:30 UTC = afternoon IST
    Instant fri = Instant.parse("2026-07-24T09:30:00Z");
    PayoutCycle.Window cur = PayoutCycle.current(fri);
    assertThat(cur.from()).isEqualTo(LocalDate.of(2026, 7, 20));
    assertThat(cur.to()).isEqualTo(LocalDate.of(2026, 7, 26));
    PayoutCycle.Window prev = PayoutCycle.previous(fri);
    assertThat(prev.from()).isEqualTo(LocalDate.of(2026, 7, 13));
    assertThat(prev.to()).isEqualTo(LocalDate.of(2026, 7, 19));
    assertThat(PayoutCycle.nextPayoutDate(prev)).isEqualTo(LocalDate.of(2026, 7, 20));
    assertThat(PayoutCycle.isMondayMorningWindow(Instant.parse("2026-07-27T01:00:00Z"))).isTrue();
    assertThat(PayoutCycle.startUtc(cur.from())).isBefore(PayoutCycle.endExclusiveUtc(cur.to()));
  }

  @Test
  void performanceRatesAndIncentives() {
    assertThat(PerformanceRates.ratePct(94, 100).doubleValue()).isEqualTo(94.0);
    assertThat(PerformanceRates.ratePctDouble(0, 0)).isEqualTo(0.0);
    assertThat(IncentiveRules.tripIncentiveBonusPaise()).isZero();
    assertThat(IncentiveRules.streakBonusPaise(null)).isEqualTo(10_000L);
    assertThat(IncentiveRules.streakDaysRequired(null)).isEqualTo(7);
    assertThat(IncentiveRules.minPayoutPaise(null)).isEqualTo(10_000L);
    assertThat(IncentiveRules.acceptanceAlertThresholdPct(null)).isEqualTo(70);
    assertThat(IncentiveRules.streakBonusPaise(cfg("rider_streak_bonus_paise", "x")))
        .isEqualTo(10_000L);
    assertThat(IncentiveRules.streakBonusPaise(cfg("rider_streak_bonus_paise", "12000")))
        .isEqualTo(12_000L);
    assertThat(
            BasePayFormula.computePaise(
                new BigDecimal("3.5"), cfgAll("1500", "2500", "2.0", "5.0")))
        .isEqualTo(2000L);
    assertThat(
            BasePayFormula.computePaise(
                new BigDecimal("3.5"), cfgAll("1500", "2500", "bad", "5.0")))
        .isEqualTo(2000L);
    assertThat(PayoutCycle.isMondayMorningWindow(Instant.parse("2026-07-27T07:00:00Z"))).isFalse();
  }

  private static com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore cfgAll(
      String minP, String maxP, String minKm, String maxKm) {
    return new com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String k) {
        return switch (k) {
          case BasePayFormula.KEY_MIN_PAISE -> Optional.of(minP);
          case BasePayFormula.KEY_MAX_PAISE -> Optional.of(maxP);
          case BasePayFormula.KEY_MIN_KM -> Optional.of(minKm);
          case BasePayFormula.KEY_MAX_KM -> Optional.of(maxKm);
          default -> Optional.empty();
        };
      }

      @Override
      public java.math.BigDecimal handlingFeeRupees() {
        return java.math.BigDecimal.ZERO;
      }

      @Override
      public void upsert(String k, String v, String d, java.util.UUID by, java.time.Instant now) {}
    };
  }

  private static com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore cfg(
      String key, String value) {
    return new com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String k) {
        return key.equals(k) ? Optional.of(value) : Optional.empty();
      }

      @Override
      public java.math.BigDecimal handlingFeeRupees() {
        return java.math.BigDecimal.ZERO;
      }

      @Override
      public void upsert(String k, String v, String d, java.util.UUID by, java.time.Instant now) {}
    };
  }
}
