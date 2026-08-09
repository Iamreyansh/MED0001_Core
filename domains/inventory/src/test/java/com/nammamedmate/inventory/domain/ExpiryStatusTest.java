package com.nammamedmate.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ExpiryStatusTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

  @Test
  void statusAndBuckets() {
    assertThat(ExpiryStatus.of(LocalDate.of(2026, 8, 1), TODAY)).isEqualTo("EXPIRED");
    assertThat(ExpiryStatus.of(LocalDate.of(2026, 10, 31), TODAY)).isEqualTo("EXPIRING_SOON");
    assertThat(ExpiryStatus.of(LocalDate.of(2027, 8, 9), TODAY)).isEqualTo("OK");

    assertThat(ExpiryStatus.daysToExpiry(LocalDate.of(2026, 8, 10), TODAY)).isEqualTo(1);

    assertThat(ExpiryStatus.alertBucket(LocalDate.of(2026, 8, 1), TODAY)).isNull();
    assertThat(ExpiryStatus.alertBucket(LocalDate.of(2026, 8, 20), TODAY))
        .isEqualTo("UNDER_1_MONTH");
    assertThat(ExpiryStatus.alertBucket(LocalDate.of(2026, 9, 20), TODAY))
        .isEqualTo("1_TO_2_MONTHS");
    assertThat(ExpiryStatus.alertBucket(LocalDate.of(2026, 11, 1), TODAY))
        .isEqualTo("2_TO_4_MONTHS");
    assertThat(ExpiryStatus.alertBucket(LocalDate.of(2027, 8, 9), TODAY)).isNull();
  }

  @Test
  void productBatchValueAtRisk() {
    ProductBatch b =
        new ProductBatch(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            "BN",
            LocalDate.of(2026, 8, 20),
            null,
            30,
            30,
            850L,
            1000L,
            true,
            null,
            null,
            null,
            null,
            null);
    assertThat(b.valueAtRiskPaise()).isEqualTo(25500L);
    assertThat(b.isExpired(TODAY)).isFalse();
    assertThat(b.isExpired(LocalDate.of(2026, 8, 21))).isTrue();
  }

  @Test
  void expiryAlertRow_nullRacksCopied() {
    var row =
        new com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryAlertRow(
            java.util.UUID.randomUUID(), "n", "b", LocalDate.of(2026, 8, 20), 1, 100L, null);
    assertThat(row.rackLocations()).isEmpty();
  }
}
