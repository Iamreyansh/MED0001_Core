package com.nammamedmate.api.config;

import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.adapter.out.persistence.JdbcInventoryAvailabilityAdapter;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridge: order {@link InventoryAvailabilityPort} ← inventory {@link
 * InventoryAvailabilityQuery} (pharmacy_product + is_online_visible), with catalogue-mapping
 * fallback until all stock is mirrored into pharmacy_product. Leaves order's JDBC stub behind
 * {@code @ConditionalOnMissingBean}.
 */
@Configuration
public class OrderInventoryBridgeConfig {

  @Bean
  @Primary
  InventoryAvailabilityPort orderInventoryAvailabilityPort(
      InventoryAvailabilityQuery query, JdbcTemplate jdbc) {
    InventoryAvailabilityPort inventory = adapt(query);
    InventoryAvailabilityPort catalogue = new JdbcInventoryAvailabilityAdapter(jdbc);
    return new InventoryAvailabilityPort() {
      @Override
      public boolean stocksMedicine(UUID pharmacyId, UUID medicineId) {
        return inventory.stocksMedicine(pharmacyId, medicineId)
            || catalogue.stocksMedicine(pharmacyId, medicineId);
      }

      @Override
      public Optional<MedicineDetails> findMedicine(UUID medicineId) {
        return inventory.findMedicine(medicineId).or(() -> catalogue.findMedicine(medicineId));
      }

      @Override
      public List<StockLine> checkAvailability(UUID pharmacyId, List<UUID> medicineIds) {
        List<StockLine> fromInv = inventory.checkAvailability(pharmacyId, medicineIds);
        if (fromInv.isEmpty()) {
          return catalogue.checkAvailability(pharmacyId, medicineIds);
        }
        List<StockLine> merged = new ArrayList<>(fromInv.size());
        List<UUID> fallbackIds = new ArrayList<>();
        for (StockLine line : fromInv) {
          if ("NOT_MAPPED".equals(line.unavailableReason())
              || "NOT_FOUND".equals(line.unavailableReason())) {
            fallbackIds.add(line.medicineId());
          } else {
            merged.add(line);
          }
        }
        if (!fallbackIds.isEmpty()) {
          merged.addAll(catalogue.checkAvailability(pharmacyId, fallbackIds));
        }
        return merged;
      }

      @Override
      public ProductPage listVisibleProducts(
          UUID pharmacyId, String category, String search, int page, int limit) {
        ProductPage fromInv =
            inventory.listVisibleProducts(pharmacyId, category, search, page, limit);
        if (fromInv.total() > 0) {
          return fromInv;
        }
        return catalogue.listVisibleProducts(pharmacyId, category, search, page, limit);
      }

      @Override
      public Optional<String> medicineName(UUID medicineId) {
        return inventory.medicineName(medicineId).or(() -> catalogue.medicineName(medicineId));
      }

      @Override
      public void reserveForOrder(UUID pharmacyId, UUID orderId, List<ReserveLine> lines) {
        if (lines == null) {
          return;
        }
        for (ReserveLine line : lines) {
          if (line == null || line.medicineId() == null || line.quantity() <= 0) {
            continue;
          }
          int updated =
              jdbc.update(
                  """
                  UPDATE pharmacy_product
                     SET total_stock_units = total_stock_units - ?, updated_at = NOW()
                   WHERE pharmacy_id = ?
                     AND master_medicine_id = ?
                     AND deleted_at IS NULL
                     AND total_stock_units >= ?
                  """,
                  line.quantity(),
                  pharmacyId,
                  line.medicineId(),
                  line.quantity());
          if (updated != 1) {
            updated =
                jdbc.update(
                    """
                    UPDATE pharmacy_catalogue_mapping
                       SET stock_quantity = stock_quantity - ?, updated_at = NOW()
                     WHERE pharmacy_id = ?
                       AND master_medicine_id = ?
                       AND stock_quantity >= ?
                    """,
                    line.quantity(),
                    pharmacyId,
                    line.medicineId(),
                    line.quantity());
          }
          if (updated != 1) {
            throw new AppException(
                "ITEMS_OUT_OF_STOCK", "One or more items unavailable to reserve", 422);
          }
          jdbc.update(
              """
              INSERT INTO inventory_reservation
                (id, order_id, pharmacy_id, medicine_id, quantity, status, created_at, updated_at)
              VALUES (?, ?, ?, ?, ?, 'RESERVED', NOW(), NOW())
              ON CONFLICT (order_id, medicine_id) DO NOTHING
              """,
              UUID.randomUUID(),
              orderId,
              pharmacyId,
              line.medicineId(),
              line.quantity());
        }
      }

      @Override
      public void deductForOrder(UUID orderId) {
        jdbc.update(
            """
            UPDATE inventory_reservation
               SET status = 'DEDUCTED', updated_at = NOW()
             WHERE order_id = ? AND status = 'RESERVED'
            """,
            orderId);
      }

      @Override
      public void releaseForOrder(UUID orderId) {
        jdbc.query(
            """
            SELECT pharmacy_id, medicine_id, quantity
              FROM inventory_reservation
             WHERE order_id = ? AND status = 'RESERVED'
            """,
            rs -> {
              int restored =
                  jdbc.update(
                      """
                      UPDATE pharmacy_product
                         SET total_stock_units = total_stock_units + ?, updated_at = NOW()
                       WHERE pharmacy_id = ? AND master_medicine_id = ? AND deleted_at IS NULL
                      """,
                      rs.getInt("quantity"),
                      rs.getObject("pharmacy_id"),
                      rs.getObject("medicine_id"));
              if (restored != 1) {
                jdbc.update(
                    """
                    UPDATE pharmacy_catalogue_mapping
                       SET stock_quantity = stock_quantity + ?, updated_at = NOW()
                     WHERE pharmacy_id = ? AND master_medicine_id = ?
                    """,
                    rs.getInt("quantity"),
                    rs.getObject("pharmacy_id"),
                    rs.getObject("medicine_id"));
              }
            },
            orderId);
        jdbc.update(
            """
            UPDATE inventory_reservation
               SET status = 'RELEASED', updated_at = NOW()
             WHERE order_id = ? AND status = 'RESERVED'
            """,
            orderId);
      }
    };
  }

  private static InventoryAvailabilityPort adapt(InventoryAvailabilityQuery query) {
    return new InventoryAvailabilityPort() {
      @Override
      public boolean stocksMedicine(UUID pharmacyId, UUID medicineId) {
        return query.stocksMedicine(pharmacyId, medicineId);
      }

      @Override
      public Optional<MedicineDetails> findMedicine(UUID medicineId) {
        return query
            .findMedicine(medicineId)
            .map(
                d ->
                    new MedicineDetails(
                        d.id(),
                        d.name(),
                        d.brand(),
                        d.packSize(),
                        d.rxRequired(),
                        d.imageUrl(),
                        d.banned()));
      }

      @Override
      public List<StockLine> checkAvailability(UUID pharmacyId, List<UUID> medicineIds) {
        return query.checkAvailability(pharmacyId, medicineIds).stream()
            .map(
                s ->
                    new StockLine(
                        s.medicineId(),
                        s.name(),
                        s.quantityAvailable(),
                        s.pricePaise(),
                        s.mrpPaise(),
                        s.inStock(),
                        s.unavailableReason()))
            .toList();
      }

      @Override
      public ProductPage listVisibleProducts(
          UUID pharmacyId, String category, String search, int page, int limit) {
        var result = query.listVisibleProducts(pharmacyId, category, search, page, limit);
        List<ProductRow> items =
            result.items().stream()
                .map(
                    r ->
                        new ProductRow(
                            r.productId(),
                            r.name(),
                            r.brand(),
                            r.category(),
                            r.packSize(),
                            r.mrpPaise(),
                            r.sellingPricePaise(),
                            r.rxRequired(),
                            r.quantityAvailable(),
                            r.imageUrl()))
                .toList();
        return new ProductPage(items, result.total(), result.page(), result.limit());
      }

      @Override
      public Optional<String> medicineName(UUID medicineId) {
        return query.medicineName(medicineId);
      }
    };
  }
}
