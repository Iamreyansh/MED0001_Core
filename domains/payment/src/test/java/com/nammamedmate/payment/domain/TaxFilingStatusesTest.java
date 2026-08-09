package com.nammamedmate.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TaxFilingStatusesTest {

  @Test
  void displayStatusOverlay() {
    LocalDate due = LocalDate.of(2026, 8, 10);
    assertThat(
            TaxFilingStatuses.displayStatus(TaxFilingStatuses.FILED, due, LocalDate.of(2026, 9, 1)))
        .isEqualTo(TaxFilingStatuses.FILED);
    assertThat(
            TaxFilingStatuses.displayStatus(
                TaxFilingStatuses.PENDING, due, LocalDate.of(2026, 8, 11)))
        .isEqualTo(TaxFilingStatuses.OVERDUE);
    assertThat(
            TaxFilingStatuses.displayStatus(
                TaxFilingStatuses.OVERDUE, due, LocalDate.of(2026, 8, 1)))
        .isEqualTo(TaxFilingStatuses.OVERDUE);
    assertThat(
            TaxFilingStatuses.displayStatus(
                TaxFilingStatuses.PENDING, due, LocalDate.of(2026, 8, 10)))
        .isEqualTo(TaxFilingStatuses.PENDING);
    assertThat(TaxFilingStatuses.displayStatus(TaxFilingStatuses.PENDING, null, LocalDate.now()))
        .isEqualTo(TaxFilingStatuses.PENDING);
    assertThat(TaxFilingStatuses.displayStatus(TaxFilingStatuses.PENDING, due, null))
        .isEqualTo(TaxFilingStatuses.PENDING);
  }
}
