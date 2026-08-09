package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.RackLocation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RackLocationStore {

  record ListFilter(UUID pharmacyId, String zone, String q, int page, int limit) {}

  record ListRow(RackLocation rack, long medicineCount, List<PharmacyProduct> preview) {
    public ListRow {
      preview = preview == null ? List.of() : List.copyOf(preview);
    }
  }

  record ListResult(List<ListRow> rows, long total) {
    public ListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record Kpi(long racksCount, long zonesCount, long medicinesMappedCount, long unlocatedCount) {}

  record ProductPreview(UUID productId, String name) {}

  Optional<RackLocation> findByCode(UUID pharmacyId, String rackCode);

  ListResult list(ListFilter filter);

  Kpi kpi(UUID pharmacyId);

  RackLocation insert(RackLocation rack);

  Optional<RackLocation> softDelete(UUID pharmacyId, String rackCode, Instant now);

  List<PharmacyProduct> medicinesInRack(UUID pharmacyId, String rackCode);

  List<ProductPreview> blockingProducts(UUID pharmacyId, String rackCode, int limit);

  long medicineCount(UUID pharmacyId, String rackCode);

  List<RackLocation> findByCodes(UUID pharmacyId, List<String> rackCodes);

  record UnlocatedPage(List<PharmacyProduct> products, long total) {
    public UnlocatedPage {
      products = products == null ? List.of() : List.copyOf(products);
    }
  }

  UnlocatedPage unlocated(UUID pharmacyId, int page, int limit);

  /** Appends rack_code to products that do not already have it. Returns newly assigned ids. */
  List<UUID> assignRack(UUID pharmacyId, List<UUID> productIds, String rackCode, Instant now);

  Optional<PharmacyProduct> addRackToProduct(
      UUID pharmacyId, UUID productId, String rackCode, Instant now);

  Optional<PharmacyProduct> removeRackFromProduct(
      UUID pharmacyId, UUID productId, String rackCode, Instant now);
}
