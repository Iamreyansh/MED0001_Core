package com.nammamedmate.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryFlagsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void lowStock_requiresPositiveReorder() {
    assertThat(InventoryFlags.isLowStock(10, 0)).isFalse();
    assertThat(InventoryFlags.isLowStock(10, 10)).isTrue();
    assertThat(InventoryFlags.isLowStock(11, 10)).isFalse();
  }

  @Test
  void expiring_withinFourMonths() {
    assertThat(InventoryFlags.isExpiring(LocalDate.of(2026, 12, 1), CLOCK)).isTrue();
    assertThat(InventoryFlags.isExpiring(LocalDate.of(2027, 1, 1), CLOCK)).isFalse();
    assertThat(InventoryFlags.isExpiring(null, CLOCK)).isFalse();
  }

  @Test
  void deadStock_nullOrOlderThan90Days() {
    assertThat(InventoryFlags.isDeadStock(null, CLOCK)).isTrue();
    assertThat(InventoryFlags.isDeadStock(Instant.parse("2026-01-01T00:00:00Z"), CLOCK)).isTrue();
    assertThat(InventoryFlags.isDeadStock(Instant.parse("2026-08-01T00:00:00Z"), CLOCK)).isFalse();
  }

  @Test
  void matchesTab_variants() {
    assertThat(
            InventoryFlags.matchesTab(
                "OUT_OF_STOCK", 0, 0, null, Instant.now(), false, List.of("A"), CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab(
                "UNALLOCATED", 5, 0, null, Instant.now(), false, List.of(), CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab(
                "RX_ONLY", 5, 0, null, Instant.now(), true, List.of("A"), CLOCK))
        .isTrue();
    assertThat(InventoryFlags.matchesTab("ALERTS", 5, 10, null, null, false, List.of("A"), CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab("ALL", 5, 0, null, Instant.now(), false, List.of("A"), CLOCK))
        .isTrue();
  }

  @Test
  void matchesTab_allBranches() {
    assertThat(
            InventoryFlags.matchesTab(
                null, 5, 10, LocalDate.of(2026, 9, 1), Instant.now(), false, List.of("A"), CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab(
                "LOW_STOCK", 5, 10, null, Instant.now(), false, List.of("A"), CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab(
                "EXPIRING",
                5,
                0,
                LocalDate.of(2026, 9, 1),
                Instant.now(),
                false,
                List.of("A"),
                CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab(
                "OUT_OF_STOCK", 1, 0, null, Instant.now(), false, List.of("A"), CLOCK))
        .isFalse();
    assertThat(
            InventoryFlags.matchesTab(
                "UNALLOCATED", 5, 0, null, Instant.now(), false, List.of(), CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab("UNALLOCATED", 5, 0, null, Instant.now(), false, null, CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab(
                "UNALLOCATED", 5, 0, null, Instant.now(), false, List.of("A1"), CLOCK))
        .isFalse();
    // ALERTS via EXPIRING only (not low-stock, not dead)
    assertThat(
            InventoryFlags.matchesTab(
                "ALERTS",
                100,
                0,
                LocalDate.of(2026, 9, 1),
                Instant.parse("2026-08-01T00:00:00Z"),
                false,
                List.of("A"),
                CLOCK))
        .isTrue();
    // ALERTS via dead_stock only
    assertThat(
            InventoryFlags.matchesTab(
                "ALERTS", 100, 0, LocalDate.of(2027, 9, 1), null, false, List.of("A"), CLOCK))
        .isTrue();
    assertThat(
            InventoryFlags.matchesTab(
                "ALERTS",
                100,
                0,
                LocalDate.of(2027, 9, 1),
                Instant.parse("2026-08-01T00:00:00Z"),
                false,
                List.of("A"),
                CLOCK))
        .isFalse();
    assertThat(
            InventoryFlags.matchesTab(
                "weird", 5, 0, null, Instant.now(), false, List.of("A"), CLOCK))
        .isTrue();
    assertThat(InventoryFlags.matchesTab(" ", 5, 0, null, Instant.now(), false, null, CLOCK))
        .isTrue();
  }
}
