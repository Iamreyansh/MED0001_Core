package com.nammamedmate.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DistributorTest {

  @Test
  void usableRequiresActiveAndNotDeleted() {
    Instant now = Instant.parse("2026-08-09T00:00:00Z");
    UUID id = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    assertThat(Distributor.minimal(id, pharmacy, "A", true, now).usable()).isTrue();
    assertThat(Distributor.minimal(id, pharmacy, "A", true, now).withActive(false).usable())
        .isFalse();
    assertThat(Distributor.minimal(id, pharmacy, "A", true, now).withDeletedAt(now).usable())
        .isFalse();
    assertThat(Distributor.minimal(id, pharmacy, "A", true, now).onCredit()).isFalse();
    assertThat(
            new Distributor(
                    id,
                    pharmacy,
                    "A",
                    null,
                    "+919876543210",
                    null,
                    null,
                    null,
                    null,
                    30,
                    0L,
                    true,
                    now,
                    now,
                    null)
                .onCredit())
        .isTrue();
  }
}

class DistributorFormatsTest {

  @Test
  void validatesGstinPhoneEmailAndLandedCost() {
    assertThat(DistributorFormats.isValidGstin("27AABCM1234A1Z5")).isTrue();
    assertThat(DistributorFormats.isValidGstin("INVALIDGSTIN")).isFalse();
    assertThat(DistributorFormats.isValidPhone("+919876543210")).isTrue();
    assertThat(DistributorFormats.isValidPhone("+911234567890")).isFalse();
    assertThat(DistributorFormats.isValidEmail("a@b.co")).isTrue();
    assertThat(DistributorFormats.isValidEmail("bad")).isFalse();

    assertThat(DistributorFormats.schemeDescription(0, 10)).isNull();
    assertThat(DistributorFormats.schemeDescription(1, 10)).isEqualTo("1 free on 10");

    assertThat(DistributorFormats.effectiveLandedCostPaise(1300, "1 free on 10"))
        .isEqualByComparingTo("11.82");
    assertThat(DistributorFormats.effectiveLandedCostPaise(1300, null))
        .isEqualByComparingTo("13.00");
    assertThat(DistributorFormats.marginPct(new BigDecimal("22.50"), new BigDecimal("11.82")))
        .isEqualByComparingTo("47.5");
    assertThat(DistributorFormats.marginPct(BigDecimal.ZERO, new BigDecimal("1")))
        .isEqualByComparingTo("0.0");
  }
}
