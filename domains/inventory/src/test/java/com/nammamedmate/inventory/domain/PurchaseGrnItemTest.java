package com.nammamedmate.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseGrnItemTest {

  @Test
  void moneyHelpersExcludeFreeQtyFromTaxable() {
    long taxable = PurchaseGrnItem.taxablePaise(100, 1300);
    long gst = PurchaseGrnItem.gstPaise(taxable, 12);
    assertThat(taxable).isEqualTo(130_000L);
    assertThat(gst).isEqualTo(15_600L);
    assertThat(PurchaseGrnItem.lineTotalPaise(taxable, gst)).isEqualTo(145_600L);

    PurchaseGrnItem item =
        new PurchaseGrnItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "BN",
            LocalDate.of(2027, 1, 1),
            null,
            100,
            20,
            1300,
            2250,
            12,
            taxable,
            gst,
            145_600L,
            false,
            Instant.parse("2026-08-09T00:00:00Z"),
            Instant.parse("2026-08-09T00:00:00Z"));
    assertThat(item.quantityTotal()).isEqualTo(120);
  }
}
