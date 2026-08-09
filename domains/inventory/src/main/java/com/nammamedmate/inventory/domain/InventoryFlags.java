package com.nammamedmate.inventory.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Computes list/detail alert flags for a pharmacy product. */
public final class InventoryFlags {

  private InventoryFlags() {}

  public static boolean isLowStock(int totalStockUnits, int reorderLevel) {
    return reorderLevel > 0 && totalStockUnits <= reorderLevel;
  }

  public static boolean isExpiring(LocalDate earliestExpiry, Clock clock) {
    if (earliestExpiry == null) {
      return false;
    }
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    LocalDate cutoff = today.plusMonths(4);
    return !earliestExpiry.isAfter(cutoff);
  }

  public static boolean isDeadStock(Instant lastMovementAt, Clock clock) {
    Instant now = clock.instant();
    if (lastMovementAt == null) {
      return true;
    }
    return lastMovementAt.isBefore(now.minus(90, ChronoUnit.DAYS));
  }

  public static List<String> flags(
      int totalStockUnits,
      int reorderLevel,
      LocalDate earliestExpiry,
      Instant lastMovementAt,
      Clock clock) {
    List<String> out = new ArrayList<>(3);
    if (isLowStock(totalStockUnits, reorderLevel)) {
      out.add("LOW_STOCK");
    }
    if (isExpiring(earliestExpiry, clock)) {
      out.add("EXPIRING");
    }
    if (isDeadStock(lastMovementAt, clock)) {
      out.add("dead_stock");
    }
    return List.copyOf(out);
  }

  public static boolean matchesTab(
      String tab,
      int totalStockUnits,
      int reorderLevel,
      LocalDate earliestExpiry,
      Instant lastMovementAt,
      boolean isRxOnly,
      List<String> rackLocations,
      Clock clock) {
    String t = tab == null || tab.isBlank() ? "ALL" : tab.trim().toUpperCase();
    return switch (t) {
      case "ALL" -> true;
      case "LOW_STOCK" -> isLowStock(totalStockUnits, reorderLevel);
      case "EXPIRING" -> isExpiring(earliestExpiry, clock);
      case "RX_ONLY" -> isRxOnly;
      case "OUT_OF_STOCK" -> totalStockUnits == 0;
      case "UNALLOCATED" -> rackLocations == null || rackLocations.isEmpty();
      case "ALERTS" ->
          isLowStock(totalStockUnits, reorderLevel)
              || isExpiring(earliestExpiry, clock)
              || isDeadStock(lastMovementAt, clock);
      default -> true;
    };
  }
}
