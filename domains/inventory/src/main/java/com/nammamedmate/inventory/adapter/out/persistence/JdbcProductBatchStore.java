package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.domain.ProductBatch;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductBatchStore implements ProductBatchStore {

  private static final String SELECT_BATCH =
      """
      SELECT id, product_id, pharmacy_id, batch_number, expiry_date, manufactured_date,
             quantity_received, quantity_current, purchase_price_paise, mrp_paise,
             is_active, write_off_reason, write_off_notes, grn_item_id, created_at, updated_at
        FROM product_batch
      """;

  private final JdbcTemplate jdbc;

  public JdbcProductBatchStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ProductBatch> listByProduct(
      UUID pharmacyId, UUID productId, boolean includeInactive) {
    if (includeInactive) {
      return jdbc.query(
          SELECT_BATCH
              + " WHERE pharmacy_id = ? AND product_id = ? ORDER BY expiry_date ASC, created_at ASC",
          ROW_MAPPER,
          pharmacyId,
          productId);
    }
    return jdbc.query(
        SELECT_BATCH
            + " WHERE pharmacy_id = ? AND product_id = ? AND is_active = TRUE"
            + " ORDER BY expiry_date ASC, created_at ASC",
        ROW_MAPPER,
        pharmacyId,
        productId);
  }

  @Override
  public Optional<ProductBatch> findById(UUID pharmacyId, UUID productId, UUID batchId) {
    List<ProductBatch> rows =
        jdbc.query(
            SELECT_BATCH + " WHERE pharmacy_id = ? AND product_id = ? AND id = ?",
            ROW_MAPPER,
            pharmacyId,
            productId,
            batchId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<ProductBatch> findByBatchNumber(
      UUID pharmacyId, UUID productId, String batchNumber) {
    List<ProductBatch> rows =
        jdbc.query(
            SELECT_BATCH + " WHERE pharmacy_id = ? AND product_id = ? AND batch_number = ?",
            ROW_MAPPER,
            pharmacyId,
            productId,
            batchNumber);
    return rows.stream().findFirst();
  }

  @Override
  public ProductBatch insert(ProductBatch batch) {
    jdbc.update(
        """
        INSERT INTO product_batch (
          id, product_id, pharmacy_id, batch_number, expiry_date, manufactured_date,
          quantity_received, quantity_current, purchase_price_paise, mrp_paise,
          is_active, write_off_reason, write_off_notes, grn_item_id, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        batch.id(),
        batch.productId(),
        batch.pharmacyId(),
        batch.batchNumber(),
        Date.valueOf(batch.expiryDate()),
        batch.manufacturedDate() == null ? null : Date.valueOf(batch.manufacturedDate()),
        batch.quantityReceived(),
        batch.quantityCurrent(),
        batch.purchasePricePaise(),
        batch.mrpPaise(),
        batch.isActive(),
        batch.writeOffReason(),
        batch.writeOffNotes(),
        batch.grnItemId(),
        Timestamp.from(batch.createdAt()),
        Timestamp.from(batch.updatedAt()));
    return batch;
  }

  @Override
  public ProductBatch updateQuantities(
      UUID batchId,
      int quantityReceived,
      int quantityCurrent,
      boolean isActive,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE product_batch
           SET quantity_received = ?, quantity_current = ?, is_active = ?, updated_at = ?
         WHERE id = ?
        """,
        quantityReceived,
        quantityCurrent,
        isActive,
        Timestamp.from(updatedAt),
        batchId);
    List<ProductBatch> rows = jdbc.query(SELECT_BATCH + " WHERE id = ?", ROW_MAPPER, batchId);
    return rows.get(0);
  }

  @Override
  public ProductBatch topUpFromGrn(
      UUID batchId,
      int quantityReceived,
      int quantityCurrent,
      long purchasePricePaise,
      long mrpPaise,
      UUID grnItemId,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE product_batch
           SET quantity_received = ?, quantity_current = ?, purchase_price_paise = ?,
               mrp_paise = ?, grn_item_id = ?, is_active = TRUE, updated_at = ?
         WHERE id = ?
        """,
        quantityReceived,
        quantityCurrent,
        purchasePricePaise,
        mrpPaise,
        grnItemId,
        Timestamp.from(updatedAt),
        batchId);
    List<ProductBatch> rows = jdbc.query(SELECT_BATCH + " WHERE id = ?", ROW_MAPPER, batchId);
    return rows.get(0);
  }

  @Override
  public ProductBatch writeOff(
      UUID batchId, String writeOffReason, String writeOffNotes, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE product_batch
           SET quantity_current = 0, is_active = FALSE,
               write_off_reason = ?, write_off_notes = ?, updated_at = ?
         WHERE id = ?
        """,
        writeOffReason,
        writeOffNotes,
        Timestamp.from(updatedAt),
        batchId);
    List<ProductBatch> rows = jdbc.query(SELECT_BATCH + " WHERE id = ?", ROW_MAPPER, batchId);
    return rows.get(0);
  }

  @Override
  public void insertAdjustmentLog(AdjustmentLogRow row) {
    jdbc.update(
        """
        INSERT INTO batch_adjustment_log (
          id, batch_id, pharmacy_id, staff_id, adjustment, reason, before_qty, after_qty, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?)
        """,
        row.id(),
        row.batchId(),
        row.pharmacyId(),
        row.staffId(),
        row.adjustment(),
        row.reason(),
        row.beforeQty(),
        row.afterQty(),
        Timestamp.from(row.createdAt()));
  }

  @Override
  public void insertStockMovement(
      UUID id,
      UUID pharmacyId,
      UUID productId,
      UUID batchId,
      String movementType,
      int quantityDelta,
      String reason,
      UUID staffId,
      Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO inventory_stock_movement (
          id, pharmacy_id, product_id, batch_id, movement_type, quantity_delta, reason, staff_id, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?)
        """,
        id,
        pharmacyId,
        productId,
        batchId,
        movementType,
        quantityDelta,
        reason,
        staffId,
        Timestamp.from(createdAt));
  }

  @Override
  public void refreshProductDenorm(UUID pharmacyId, UUID productId, Instant now) {
    ProductStockAgg agg = aggregateActive(pharmacyId, productId);
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
        agg.totalStockUnits(),
        agg.totalBatches(),
        agg.earliestExpiry() == null ? null : Date.valueOf(agg.earliestExpiry()),
        agg.costValuePaise(),
        Timestamp.from(now),
        Timestamp.from(now),
        pharmacyId,
        productId);
    jdbc.update(
        """
        UPDATE pharmacy_catalogue_mapping pcm
           SET stock_quantity = pp.total_stock_units, updated_at = ?
          FROM pharmacy_product pp
         WHERE pp.id = ?
           AND pp.pharmacy_id = ?
           AND pp.pharmacy_id = pcm.pharmacy_id
           AND pp.master_medicine_id IS NOT NULL
           AND pp.master_medicine_id = pcm.master_medicine_id
        """,
        Timestamp.from(now),
        productId,
        pharmacyId);
  }

  @Override
  public ProductStockAgg aggregateActive(UUID pharmacyId, UUID productId) {
    return jdbc.query(
        """
        SELECT
          COALESCE(SUM(quantity_current), 0) AS total_stock_units,
          COALESCE(SUM(CASE WHEN is_active AND quantity_current > 0 THEN 1 ELSE 0 END), 0) AS total_batches,
          MIN(CASE WHEN is_active AND quantity_current > 0 THEN expiry_date END) AS earliest_expiry,
          COALESCE(SUM(CASE WHEN is_active THEN quantity_current * purchase_price_paise ELSE 0 END), 0)
            AS cost_value_paise
        FROM product_batch
        WHERE pharmacy_id = ? AND product_id = ?
        """,
        rs -> {
          rs.next();
          Date expiry = rs.getDate("earliest_expiry");
          return new ProductStockAgg(
              rs.getInt("total_stock_units"),
              rs.getInt("total_batches"),
              expiry == null ? null : expiry.toLocalDate(),
              rs.getLong("cost_value_paise"));
        },
        pharmacyId,
        productId);
  }

  @Override
  public List<ProductBatch> listFefoEligible(UUID pharmacyId, UUID productId, LocalDate today) {
    return jdbc.query(
        SELECT_BATCH
            + """
             WHERE pharmacy_id = ? AND product_id = ?
               AND is_active = TRUE AND quantity_current > 0 AND expiry_date >= ?
             ORDER BY expiry_date ASC, created_at ASC
            """,
        ROW_MAPPER,
        pharmacyId,
        productId,
        Date.valueOf(today));
  }

  @Override
  public List<ExpiryAlertRow> listExpiringWithinMonths(
      UUID pharmacyId, int withinMonths, LocalDate today) {
    LocalDate cutoff = today.plusMonths(withinMonths);
    return jdbc.query(
        """
        SELECT p.id AS product_id, p.name AS product_name, b.batch_number, b.expiry_date,
               b.quantity_current, b.purchase_price_paise, p.rack_locations
          FROM product_batch b
          JOIN pharmacy_product p ON p.id = b.product_id AND p.deleted_at IS NULL
         WHERE b.pharmacy_id = ?
           AND b.is_active = TRUE
           AND b.quantity_current > 0
           AND b.expiry_date >= ?
           AND b.expiry_date <= ?
         ORDER BY b.expiry_date ASC, p.name ASC
        """,
        (rs, i) ->
            new ExpiryAlertRow(
                (UUID) rs.getObject("product_id"),
                rs.getString("product_name"),
                rs.getString("batch_number"),
                rs.getDate("expiry_date").toLocalDate(),
                rs.getInt("quantity_current"),
                rs.getLong("purchase_price_paise"),
                readTextArray(rs.getArray("rack_locations"))),
        pharmacyId,
        Date.valueOf(today),
        Date.valueOf(cutoff));
  }

  @Override
  public List<ExpiryReportRow> listExpiryReport(
      UUID pharmacyId, int withinMonths, LocalDate today) {
    LocalDate cutoff = today.plusMonths(withinMonths);
    return jdbc.query(
        """
        SELECT p.name AS product_name, b.batch_number, b.expiry_date,
               b.quantity_current, b.purchase_price_paise,
               CASE WHEN p.rack_locations IS NULL OR cardinality(p.rack_locations) = 0
                    THEN NULL ELSE p.rack_locations[1] END AS rack_location
          FROM product_batch b
          JOIN pharmacy_product p ON p.id = b.product_id AND p.deleted_at IS NULL
         WHERE b.pharmacy_id = ?
           AND b.is_active = TRUE
           AND b.quantity_current > 0
           AND b.expiry_date >= ?
           AND b.expiry_date <= ?
         ORDER BY b.expiry_date ASC, p.name ASC
        """,
        (rs, i) ->
            new ExpiryReportRow(
                rs.getString("product_name"),
                rs.getString("batch_number"),
                rs.getDate("expiry_date").toLocalDate(),
                rs.getInt("quantity_current"),
                rs.getLong("purchase_price_paise"),
                rs.getString("rack_location")),
        pharmacyId,
        Date.valueOf(today),
        Date.valueOf(cutoff));
  }

  @Override
  public List<AdjustmentLogRow> listAdjustments(UUID batchId) {
    return jdbc.query(
        """
        SELECT id, batch_id, pharmacy_id, staff_id, adjustment, reason, before_qty, after_qty, created_at
          FROM batch_adjustment_log
         WHERE batch_id = ?
         ORDER BY created_at DESC
        """,
        (rs, i) ->
            new AdjustmentLogRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("batch_id"),
                (UUID) rs.getObject("pharmacy_id"),
                (UUID) rs.getObject("staff_id"),
                rs.getInt("adjustment"),
                rs.getString("reason"),
                rs.getInt("before_qty"),
                rs.getInt("after_qty"),
                rs.getTimestamp("created_at").toInstant()),
        batchId);
  }

  private static final RowMapper<ProductBatch> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

  static ProductBatch mapRow(ResultSet rs) throws SQLException {
    Date manufactured = rs.getDate("manufactured_date");
    Date expiry = rs.getDate("expiry_date");
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    return new ProductBatch(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("product_id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("batch_number"),
        expiry.toLocalDate(),
        manufactured == null ? null : manufactured.toLocalDate(),
        rs.getInt("quantity_received"),
        rs.getInt("quantity_current"),
        rs.getLong("purchase_price_paise"),
        rs.getLong("mrp_paise"),
        rs.getBoolean("is_active"),
        rs.getString("write_off_reason"),
        rs.getString("write_off_notes"),
        (UUID) rs.getObject("grn_item_id"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }

  static List<String> readTextArray(java.sql.Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] strings) {
      return Arrays.asList(strings);
    }
    if (raw instanceof Object[] objs) {
      List<String> out = new ArrayList<>(objs.length);
      for (Object o : objs) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return out;
    }
    return List.of();
  }
}
