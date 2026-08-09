package com.nammamedmate.pos.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Product lookup for POS — bridged to inventory JDBC in apps/api. */
public interface ProductLookupPort {

  record ProductSnapshot(
      UUID productId,
      String name,
      String manufacturer,
      String form,
      int packSize,
      long mrpPaise,
      int totalStockUnits,
      boolean isRxOnly,
      boolean isLooseSellingEnabled,
      BigDecimal gstPct,
      String hsnCode,
      List<String> rackLocations) {

    public ProductSnapshot {
      rackLocations = rackLocations == null ? List.of() : List.copyOf(rackLocations);
    }
  }

  record BatchOption(
      UUID batchId,
      String batchNumber,
      LocalDate expiryDate,
      int quantityCurrent,
      boolean fefoFirst) {}

  record SearchHit(ProductSnapshot product, List<BatchOption> batches, boolean autoAdd) {
    public SearchHit {
      batches = batches == null ? List.of() : List.copyOf(batches);
    }
  }

  Optional<ProductSnapshot> findById(UUID pharmacyId, UUID productId);

  Optional<ProductSnapshot> findByBarcode(UUID pharmacyId, String barcode);

  List<SearchHit> searchByText(UUID pharmacyId, String query, int limit);

  List<SearchHit> searchByRack(UUID pharmacyId, String rackCode, int limit);
}
