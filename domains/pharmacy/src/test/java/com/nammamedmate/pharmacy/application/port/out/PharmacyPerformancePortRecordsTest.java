package com.nammamedmate.pharmacy.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.PerformanceAlertStore.AlertRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.OrderListResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.RatingListResult;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyPerformancePortRecordsTest {

  private static final UUID PID = Ids.newId();
  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");

  @Test
  void ratingListResultCopiesNonNullCollections() {
    RatingListResult withData =
        new RatingListResult(new BigDecimal("4.5"), 2, Map.of(5, 2), List.of(), 2L);
    assertThat(withData.distribution()).containsEntry(5, 2);
    assertThat(withData.ratings()).isEmpty();

    RatingListResult empty = new RatingListResult(null, 0, null, null, 0L);
    assertThat(empty.distribution()).isEmpty();
    assertThat(empty.ratings()).isEmpty();
  }

  @Test
  void orderListResultCopiesOrders() {
    assertThat(new OrderListResult(List.of(), 0L).orders()).isEmpty();
    assertThat(new OrderListResult(null, 0L).orders()).isEmpty();
  }

  @Test
  void settlementListResultCopiesNullSettlements() {
    assertThat(new ListResult(null, 0L).settlements()).isEmpty();
    assertThat(new ListResult(List.of(), 1L).settlements()).isEmpty();
  }

  @Test
  void alertRowCopiesChannels() {
    assertThat(
            new AlertRow(
                    Ids.newId(),
                    PID,
                    "LOW_FILL_RATE",
                    null,
                    new BigDecimal("1"),
                    null,
                    List.of("WHATSAPP"),
                    NOW)
                .channels())
        .containsExactly("WHATSAPP");
    assertThat(
            new AlertRow(
                    Ids.newId(), PID, "LOW_FILL_RATE", null, new BigDecimal("1"), null, null, NOW)
                .channels())
        .isEmpty();
  }
}
