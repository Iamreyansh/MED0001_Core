package com.nammamedmate.prescription.adapter.out.persistence;

import com.nammamedmate.prescription.application.port.out.InventoryBanPort;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deactivates matching product_batch rows, zeros qty, and refreshes pharmacy_product denorm so
 * online availability cannot sell recalled stock. No domain→domain dep on inventory module.
 */
@Component
public class JdbcInventoryBanAdapter implements InventoryBanPort {

  private final JdbcTemplate jdbc;

  public JdbcInventoryBanAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public BanResult banByDrugNameAndBatch(String drugName, String batchNo) {
    if (drugName == null || drugName.isBlank() || batchNo == null || batchNo.isBlank()) {
      return new BanResult(0, List.of());
    }
    Instant now = Instant.now();
    Timestamp ts = Timestamp.from(now);
    List<AffectedProduct> affected =
        jdbc.query(
            """
            UPDATE product_batch b
            SET is_active = FALSE,
                quantity_current = 0,
                write_off_reason = 'REGULATORY',
                updated_at = ?
            FROM pharmacy_product p
            WHERE b.product_id = p.id
              AND b.batch_number = ?
              AND lower(p.name) = lower(?)
              AND p.deleted_at IS NULL
              AND (b.is_active = TRUE OR b.quantity_current > 0)
            RETURNING b.pharmacy_id, b.product_id
            """,
            (rs, i) ->
                new AffectedProduct(
                    (UUID) rs.getObject("pharmacy_id"), (UUID) rs.getObject("product_id")),
            ts,
            batchNo.trim(),
            drugName.trim());
    Set<UUID> pharmacyIds = new LinkedHashSet<>();
    Set<String> refreshed = new LinkedHashSet<>();
    for (AffectedProduct row : affected) {
      pharmacyIds.add(row.pharmacyId());
      String key = row.pharmacyId() + ":" + row.productId();
      if (refreshed.add(key)) {
        refreshProductDenorm(row.pharmacyId(), row.productId(), now);
      }
    }
    return new BanResult(affected.size(), new ArrayList<>(pharmacyIds));
  }

  private void refreshProductDenorm(UUID pharmacyId, UUID productId, Instant now) {
    jdbc.query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN is_active THEN quantity_current ELSE 0 END), 0) AS total_stock_units,
          COALESCE(SUM(CASE WHEN is_active AND quantity_current > 0 THEN 1 ELSE 0 END), 0) AS total_batches,
          MIN(CASE WHEN is_active AND quantity_current > 0 THEN expiry_date END) AS earliest_expiry,
          COALESCE(SUM(CASE WHEN is_active THEN quantity_current * purchase_price_paise ELSE 0 END), 0)
            AS cost_value_paise
        FROM product_batch
        WHERE pharmacy_id = ? AND product_id = ?
        """,
        rs -> {
          if (!rs.next()) {
            return null;
          }
          Date expiry = rs.getDate("earliest_expiry");
          jdbc.update(
              """
              UPDATE pharmacy_product
                 SET total_stock_units = ?,
                     total_batches = ?,
                     earliest_expiry = ?,
                     cost_value_paise = ?,
                     last_movement_at = ?,
                     updated_at = ?
               WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
              """,
              rs.getInt("total_stock_units"),
              rs.getInt("total_batches"),
              expiry,
              rs.getLong("cost_value_paise"),
              Timestamp.from(now),
              Timestamp.from(now),
              pharmacyId,
              productId);
          return null;
        },
        pharmacyId,
        productId);
  }

  private record AffectedProduct(UUID pharmacyId, UUID productId) {
    AffectedProduct {
      Objects.requireNonNull(pharmacyId);
      Objects.requireNonNull(productId);
    }
  }
}
