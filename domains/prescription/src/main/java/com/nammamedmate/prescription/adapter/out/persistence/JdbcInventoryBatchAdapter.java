package com.nammamedmate.prescription.adapter.out.persistence;

import com.nammamedmate.prescription.application.port.out.InventoryBatchPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ponytail: opening stock from pharmacy_product.total_stock_units + first active batch; ceiling:
 * name ILIKE match only — upgrade when dispense carries product_id.
 */
@Component
public class JdbcInventoryBatchAdapter implements InventoryBatchPort {

  private final JdbcTemplate jdbc;

  public JdbcInventoryBatchAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<OpeningStock> findOpeningStock(UUID pharmacyId, String drugName) {
    if (drugName == null || drugName.isBlank()) {
      return Optional.empty();
    }
    List<OpeningStock> rows =
        jdbc.query(
            """
            SELECT COALESCE(p.total_stock_units, 0) AS qty, b.batch_number
            FROM pharmacy_product p
            LEFT JOIN LATERAL (
              SELECT batch_number
              FROM product_batch
              WHERE pharmacy_id = p.pharmacy_id AND product_id = p.id AND is_active = TRUE
              ORDER BY expiry_date ASC
              LIMIT 1
            ) b ON TRUE
            WHERE p.pharmacy_id = ? AND p.deleted_at IS NULL
              AND lower(p.name) = lower(?)
            LIMIT 1
            """,
            (rs, i) -> new OpeningStock(rs.getString("batch_number"), rs.getInt("qty")),
            pharmacyId,
            drugName.trim());
    return rows.stream().findFirst();
  }
}
