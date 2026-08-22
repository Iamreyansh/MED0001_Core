package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.ProductBatch;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductBatchStore {

  record AdjustmentLogRow(
      UUID id,
      UUID batchId,
      UUID pharmacyId,
      UUID staffId,
      int adjustment,
      String reason,
      int beforeQty,
      int afterQty,
      Instant createdAt) {}

  record ExpiryAlertRow(
      UUID productId,
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      int quantityCurrent,
      long purchasePricePaise,
      List<String> rackLocations) {
    public ExpiryAlertRow {
      rackLocations = rackLocations == null ? List.of() : List.copyOf(rackLocations);
    }
  }

  record ExpiryReportRow(
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      int quantityCurrent,
      long purchasePricePaise,
      String rackLocation) {}

  record ProductStockAgg(
      int totalStockUnits, int totalBatches, LocalDate earliestExpiry, long costValuePaise) {}

  List<ProductBatch> listByProduct(UUID pharmacyId, UUID productId, boolean includeInactive);

  Optional<ProductBatch> findById(UUID pharmacyId, UUID productId, UUID batchId);

  Optional<ProductBatch> findByBatchNumber(UUID pharmacyId, UUID productId, String batchNumber);

  ProductBatch insert(ProductBatch batch);

  ProductBatch updateQuantities(
      UUID batchId, int quantityReceived, int quantityCurrent, boolean isActive, Instant updatedAt);

  /**
   * Conditional deduct: succeeds only when {@code quantity_current >= quantity}. Returns empty when
   * the race loses.
   */
  Optional<ProductBatch> tryDeductQuantity(UUID batchId, int quantity, Instant updatedAt);

  /** Top-up from GRN: qty delta + refresh PTR/MRP + link grn_item_id. */
  ProductBatch topUpFromGrn(
      UUID batchId,
      int quantityReceived,
      int quantityCurrent,
      long purchasePricePaise,
      long mrpPaise,
      UUID grnItemId,
      Instant updatedAt);

  ProductBatch writeOff(
      UUID batchId, String writeOffReason, String writeOffNotes, Instant updatedAt);

  void insertAdjustmentLog(AdjustmentLogRow row);

  void insertStockMovement(
      UUID id,
      UUID pharmacyId,
      UUID productId,
      UUID batchId,
      String movementType,
      int quantityDelta,
      String reason,
      UUID staffId,
      Instant createdAt);

  void refreshProductDenorm(UUID pharmacyId, UUID productId, Instant now);

  ProductStockAgg aggregateActive(UUID pharmacyId, UUID productId);

  /** Active batches with qty > 0, ordered by expiry ASC (FEFO). */
  List<ProductBatch> listFefoEligible(UUID pharmacyId, UUID productId, LocalDate today);

  List<ExpiryAlertRow> listExpiringWithinMonths(UUID pharmacyId, int withinMonths, LocalDate today);

  List<ExpiryReportRow> listExpiryReport(UUID pharmacyId, int withinMonths, LocalDate today);

  List<AdjustmentLogRow> listAdjustments(UUID batchId);
}
