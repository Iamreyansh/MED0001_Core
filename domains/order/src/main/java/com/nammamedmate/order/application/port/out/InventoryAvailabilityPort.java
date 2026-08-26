package com.nammamedmate.order.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Inventory availability until EPIC-006 (catalogue mapping + medicine_master). */
public interface InventoryAvailabilityPort {

  record StockLine(
      UUID medicineId,
      String name,
      int quantityAvailable,
      long pricePaise,
      long mrpPaise,
      boolean inStock,
      String unavailableReason) {}

  record ProductRow(
      UUID productId,
      String name,
      String brand,
      String category,
      String packSize,
      long mrpPaise,
      long sellingPricePaise,
      boolean rxRequired,
      int quantityAvailable,
      String imageUrl) {}

  record ProductPage(List<ProductRow> items, long total, int page, int limit) {
    public ProductPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  record MedicineDetails(
      UUID id,
      String name,
      String brand,
      String packSize,
      boolean rxRequired,
      String imageUrl,
      boolean banned) {}

  default boolean stocksMedicine(UUID pharmacyId, UUID medicineId) {
    return false;
  }

  default Optional<MedicineDetails> findMedicine(UUID medicineId) {
    return Optional.empty();
  }

  default List<StockLine> checkAvailability(UUID pharmacyId, List<UUID> medicineIds) {
    return List.of();
  }

  default ProductPage listVisibleProducts(
      UUID pharmacyId, String category, String search, int page, int limit) {
    return new ProductPage(List.of(), 0, page, limit);
  }

  default Optional<String> medicineName(UUID medicineId) {
    return Optional.empty();
  }

  record ReserveLine(UUID medicineId, int quantity) {}

  default void reserveForOrder(UUID pharmacyId, UUID orderId, List<ReserveLine> lines) {}

  default void deductForOrder(UUID orderId) {}

  default void releaseForOrder(UUID orderId) {}
}
