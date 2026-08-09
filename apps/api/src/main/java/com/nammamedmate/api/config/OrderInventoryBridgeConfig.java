package com.nammamedmate.api.config;

import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery;
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
