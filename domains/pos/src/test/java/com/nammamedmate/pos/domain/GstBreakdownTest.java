package com.nammamedmate.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GstBreakdownTest {

  @Test
  void fromItemsNullAndOddGst() {
    assertThat(GstBreakdown.fromItems(null)).isEmpty();
    assertThat(GstBreakdown.halfGstPaise(5)).isEqualTo(2);

    Instant now = Instant.parse("2026-07-24T12:00:00Z");
    InvoiceItem a =
        new InvoiceItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "A",
            null,
            null,
            null,
            LocalDate.now(),
            1,
            1,
            false,
            100,
            12,
            89,
            5,
            100,
            false,
            now);
    List<Map<String, Object>> rows = GstBreakdown.fromItems(List.of(a));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("hsn_code")).isNull();
    assertThat(rows.getFirst().get("cgst")).isEqualTo(MoneyMath.paiseToRupees(2));
    assertThat(rows.getFirst().get("sgst")).isEqualTo(MoneyMath.paiseToRupees(3));
  }
}
