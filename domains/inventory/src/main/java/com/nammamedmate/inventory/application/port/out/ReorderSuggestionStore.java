package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.ReorderSuggestionSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReorderSuggestionStore {

  record LowStockProduct(
      UUID productId,
      String productName,
      String manufacturer,
      int currentStock,
      int reorderLevel,
      long mrpPaise,
      int gstPct) {}

  record SupplyOffer(
      UUID distributorId,
      String distributorName,
      String distributorPhone,
      long purchasePricePaise,
      String schemeDescription) {}

  record SuggestionRow(
      ReorderSuggestionSnapshot snapshot,
      String productName,
      String manufacturer,
      String bestDistributorName,
      String bestDistributorPhone) {}

  record ListResult(List<SuggestionRow> rows, long total) {
    public ListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  /** Replace all snapshots for pharmacy+date (idempotent nightly/manual refresh). */
  int replaceSnapshots(
      UUID pharmacyId, LocalDate snapshotDate, List<ReorderSuggestionSnapshot> rows);

  List<LowStockProduct> listLowStockProducts(UUID pharmacyId);

  List<SupplyOffer> listActiveOffers(UUID pharmacyId, UUID productId);

  Optional<LocalDate> latestSnapshotDate(UUID pharmacyId);

  ListResult listLatest(UUID pharmacyId, int page, int limit);

  long countLatest(UUID pharmacyId);

  Optional<Instant> latestRefreshedAt(UUID pharmacyId);

  List<UUID> listPharmacyIdsWithLowStock();
}
