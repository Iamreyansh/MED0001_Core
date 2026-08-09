package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.domain.ProductBatch;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FefoBatchSelectionServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
  private static final UUID PHARMACY = UUID.randomUUID();
  private static final UUID PRODUCT = UUID.randomUUID();

  @Mock private ProductBatchStore store;
  private FefoBatchSelectionService service;

  @BeforeEach
  void setUp() {
    service = new FefoBatchSelectionService(store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_fefoSelectsEarliestExpiry() {
    ProductBatch earlier = batch("EARLY", LocalDate.of(2026, 10, 31), 150);
    ProductBatch later = batch("LATE", LocalDate.of(2027, 3, 31), 300);
    when(store.listFefoEligible(eq(PHARMACY), eq(PRODUCT), eq(TODAY)))
        .thenReturn(List.of(earlier, later));

    Optional<ProductBatch> selected = service.selectFefoBatch(PHARMACY, PRODUCT);

    assertThat(selected).isPresent();
    assertThat(selected.get().batchNumber()).isEqualTo("EARLY");
    assertThat(service.listPosEligibleBatches(PHARMACY, PRODUCT)).hasSize(2);
  }

  @Test
  void ac_expiredBatchesExcludedFromPosList() {
    // store already filters expiry_date >= today; empty means expired-only inventory
    when(store.listFefoEligible(eq(PHARMACY), eq(PRODUCT), eq(TODAY))).thenReturn(List.of());

    assertThat(service.selectFefoBatch(PHARMACY, PRODUCT)).isEmpty();
    assertThat(service.listPosEligibleBatches(PHARMACY, PRODUCT)).isEmpty();
  }

  private static ProductBatch batch(String number, LocalDate expiry, int qty) {
    return new ProductBatch(
        UUID.randomUUID(),
        PRODUCT,
        PHARMACY,
        number,
        expiry,
        null,
        qty,
        qty,
        1000L,
        2000L,
        true,
        null,
        null,
        null,
        NOW,
        NOW);
  }
}
