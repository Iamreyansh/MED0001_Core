package com.nammamedmate.api.config;

import com.nammamedmate.inventory.application.port.out.FefoBatchSelectionPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.ProductBatch;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Composition-root: POS inventory ports ← inventory FEFO / batch store / pharmacy_product. */
@Configuration
public class PosInventoryBridgeConfig {

  @Bean
  @Primary
  PosFefoPort posFefoPort(FefoBatchSelectionPort fefo, ProductBatchStore batches) {
    return new PosFefoPort() {
      @Override
      public Optional<BatchSnapshot> selectFefoBatch(UUID pharmacyId, UUID productId) {
        return fefo.selectFefoBatch(pharmacyId, productId).map(PosInventoryBridgeConfig::toSnap);
      }

      @Override
      public List<BatchSnapshot> listEligibleBatches(UUID pharmacyId, UUID productId) {
        return fefo.listPosEligibleBatches(pharmacyId, productId).stream()
            .map(PosInventoryBridgeConfig::toSnap)
            .toList();
      }

      @Override
      public Optional<BatchSnapshot> findBatch(UUID pharmacyId, UUID productId, UUID batchId) {
        return batches
            .findById(pharmacyId, productId, batchId)
            .filter(ProductBatch::isActive)
            .filter(b -> b.quantityCurrent() > 0)
            .map(PosInventoryBridgeConfig::toSnap);
      }
    };
  }

  @Bean
  @Primary
  StockDeductionPort posStockDeductionPort(ProductBatchStore batches) {
    return (pharmacyId, productId, batchId, quantity, staffId, now) -> {
      ProductBatch batch =
          batches
              .findById(pharmacyId, productId, batchId)
              .orElseThrow(() -> new AppException("INSUFFICIENT_STOCK", "Batch not found", 400));
      if (!batch.isActive() || batch.quantityCurrent() < quantity) {
        throw new AppException("INSUFFICIENT_STOCK", "Stock depleted", 400);
      }
      int after = batch.quantityCurrent() - quantity;
      batches.updateQuantities(batch.id(), batch.quantityReceived(), after, after > 0, now);
      batches.insertStockMovement(
          UUID.randomUUID(),
          pharmacyId,
          productId,
          batchId,
          "SALE",
          -quantity,
          "POS_SALE",
          staffId,
          now);
      batches.refreshProductDenorm(pharmacyId, productId, now);
    };
  }

  @Bean
  @Primary
  ProductLookupPort posProductLookupPort(
      PharmacyProductStore products, PosFefoPort fefo, JdbcTemplate jdbc) {
    return new ProductLookupPort() {
      @Override
      public Optional<ProductSnapshot> findById(UUID pharmacyId, UUID productId) {
        return products.findById(pharmacyId, productId).map(PosInventoryBridgeConfig::toProduct);
      }

      @Override
      public Optional<ProductSnapshot> findByBarcode(UUID pharmacyId, String barcode) {
        List<ProductSnapshot> rows =
            jdbc.query(
                """
                SELECT id, pharmacy_id, name, manufacturer, form, pack_size, mrp_paise,
                       total_stock_units, is_rx_only, is_loose_selling_enabled, gst_pct,
                       hsn_code, rack_locations
                FROM pharmacy_product
                WHERE pharmacy_id = ? AND barcode = ? AND deleted_at IS NULL
                LIMIT 1
                """,
                PRODUCT_ROW,
                pharmacyId,
                barcode);
        return rows.stream().findFirst();
      }

      @Override
      public List<SearchHit> searchByText(UUID pharmacyId, String query, int limit) {
        List<PharmacyProduct> found = products.searchByName(pharmacyId, query, limit);
        return found.stream().map(p -> toHit(pharmacyId, p, false, fefo)).toList();
      }

      @Override
      public List<SearchHit> searchByRack(UUID pharmacyId, String rackCode, int limit) {
        List<ProductSnapshot> rows =
            jdbc.query(
                """
                SELECT id, pharmacy_id, name, manufacturer, form, pack_size, mrp_paise,
                       total_stock_units, is_rx_only, is_loose_selling_enabled, gst_pct,
                       hsn_code, rack_locations
                FROM pharmacy_product
                WHERE pharmacy_id = ? AND deleted_at IS NULL
                  AND rack_locations IS NOT NULL
                  AND ? = ANY(rack_locations)
                ORDER BY name
                LIMIT ?
                """,
                PRODUCT_ROW,
                pharmacyId,
                rackCode,
                Math.min(Math.max(limit, 1), 50));
        List<ProductLookupPort.SearchHit> hits = new ArrayList<>();
        for (ProductLookupPort.ProductSnapshot p : rows) {
          hits.add(toHitFromSnap(pharmacyId, p, false, fefo));
        }
        return hits;
      }
    };
  }

  private static ProductLookupPort.SearchHit toHit(
      UUID pharmacyId, PharmacyProduct p, boolean autoAdd, PosFefoPort fefo) {
    return toHitFromSnap(pharmacyId, toProduct(p), autoAdd, fefo);
  }

  private static ProductLookupPort.SearchHit toHitFromSnap(
      UUID pharmacyId, ProductLookupPort.ProductSnapshot p, boolean autoAdd, PosFefoPort fefo) {
    List<PosFefoPort.BatchSnapshot> batches = fefo.listEligibleBatches(pharmacyId, p.productId());
    List<ProductLookupPort.BatchOption> opts = new ArrayList<>();
    for (int i = 0; i < batches.size(); i++) {
      var b = batches.get(i);
      opts.add(
          new ProductLookupPort.BatchOption(
              b.batchId(), b.batchNumber(), b.expiryDate(), b.quantityCurrent(), i == 0));
    }
    return new ProductLookupPort.SearchHit(p, opts, autoAdd);
  }

  private static PosFefoPort.BatchSnapshot toSnap(ProductBatch b) {
    return new PosFefoPort.BatchSnapshot(
        b.id(), b.productId(), b.batchNumber(), b.expiryDate(), b.quantityCurrent(), b.mrpPaise());
  }

  private static ProductLookupPort.ProductSnapshot toProduct(PharmacyProduct p) {
    return new ProductLookupPort.ProductSnapshot(
        p.id(),
        p.name(),
        p.manufacturer(),
        p.form(),
        p.packSize(),
        p.mrpPaise(),
        p.totalStockUnits(),
        p.isRxOnly(),
        p.isLooseSellingEnabled(),
        p.gstPct() == null ? BigDecimal.valueOf(12) : p.gstPct(),
        p.hsnCode(),
        p.rackLocations());
  }

  private static final RowMapper<ProductLookupPort.ProductSnapshot> PRODUCT_ROW =
      (rs, i) ->
          new ProductLookupPort.ProductSnapshot(
              (UUID) rs.getObject("id"),
              rs.getString("name"),
              rs.getString("manufacturer"),
              rs.getString("form"),
              rs.getInt("pack_size"),
              rs.getLong("mrp_paise"),
              rs.getInt("total_stock_units"),
              rs.getBoolean("is_rx_only"),
              rs.getBoolean("is_loose_selling_enabled"),
              BigDecimal.valueOf(rs.getInt("gst_pct")),
              rs.getString("hsn_code"),
              readTextArray(rs, "rack_locations"));

  private static List<String> readTextArray(ResultSet rs, String col) throws SQLException {
    Array arr = rs.getArray(col);
    if (arr == null) {
      return List.of();
    }
    Object raw = arr.getArray();
    if (raw instanceof String[] s) {
      return Arrays.asList(s);
    }
    if (raw instanceof Object[] o) {
      return Arrays.stream(o).map(v -> v == null ? null : v.toString()).toList();
    }
    return List.of();
  }
}
