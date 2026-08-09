package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.inventory.domain.PurchaseGrnItem;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseGrnStore implements PurchaseGrnStore {

  private static final String SELECT_GRN =
      """
      SELECT id, pharmacy_id, distributor_id, invoice_number, invoice_date, status,
             stocked_at, stocked_by, created_by, import_unmatched::text AS import_unmatched,
             created_at, updated_at, deleted_at
        FROM purchase_grn
      """;

  private static final String SELECT_ITEM =
      """
      SELECT id, grn_id, pharmacy_id, product_id, batch_number, expiry_date, manufactured_date,
             quantity, free_quantity, purchase_price_paise, mrp_paise, gst_pct,
             taxable_amount_paise, gst_amount_paise, line_total_paise, is_new_product,
             created_at, updated_at
        FROM purchase_grn_item
      """;

  private final JdbcTemplate jdbc;

  public JdbcPurchaseGrnStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PurchaseGrn insert(PurchaseGrn grn) {
    jdbc.update(
        """
        INSERT INTO purchase_grn (
          id, pharmacy_id, distributor_id, invoice_number, invoice_date, status,
          stocked_at, stocked_by, created_by, import_unmatched, created_at, updated_at, deleted_at
        ) VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,NULL)
        """,
        grn.id(),
        grn.pharmacyId(),
        grn.distributorId(),
        grn.invoiceNumber(),
        Date.valueOf(grn.invoiceDate()),
        grn.status().name(),
        grn.stockedAt() == null ? null : Timestamp.from(grn.stockedAt()),
        grn.stockedBy(),
        grn.createdBy(),
        grn.importUnmatchedJson(),
        Timestamp.from(grn.createdAt()),
        Timestamp.from(grn.updatedAt()));
    return grn;
  }

  @Override
  public Optional<PurchaseGrn> findById(UUID pharmacyId, UUID grnId) {
    List<PurchaseGrn> rows =
        jdbc.query(
            SELECT_GRN + " WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL",
            GRN_MAPPER,
            pharmacyId,
            grnId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean invoiceExists(UUID pharmacyId, UUID distributorId, String invoiceNumber) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM purchase_grn
             WHERE pharmacy_id = ? AND distributor_id = ? AND invoice_number = ?
               AND deleted_at IS NULL
            """,
            Long.class,
            pharmacyId,
            distributorId,
            invoiceNumber);
    return count != null && count > 0;
  }

  @Override
  public ListResult list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE g.pharmacy_id = ? AND g.deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    args.add(filter.pharmacyId());
    if (filter.status() != null) {
      where.append(" AND g.status = ? ");
      args.add(filter.status().name());
    }
    if (filter.distributorId() != null) {
      where.append(" AND g.distributor_id = ? ");
      args.add(filter.distributorId());
    }
    if (filter.fromDate() != null) {
      where.append(" AND g.invoice_date >= ? ");
      args.add(Date.valueOf(filter.fromDate()));
    }
    if (filter.toDate() != null) {
      where.append(" AND g.invoice_date <= ? ");
      args.add(Date.valueOf(filter.toDate()));
    }
    if (filter.q() != null && !filter.q().isBlank()) {
      where.append(" AND LOWER(g.invoice_number) LIKE ? ");
      args.add("%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%");
    }

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM purchase_grn g " + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add((filter.page() - 1) * filter.limit());
    List<GrnListRow> rows =
        jdbc.query(
            """
            SELECT g.id, d.firm_name, g.invoice_number, g.invoice_date, g.status, g.created_at,
                   COALESCE(COUNT(i.id), 0) AS line_count,
                   COALESCE(SUM(i.taxable_amount_paise), 0) AS taxable_amount_paise,
                   COALESCE(SUM(i.gst_amount_paise), 0) AS gst_amount_paise,
                   COALESCE(SUM(i.line_total_paise), 0) AS total_paise
              FROM purchase_grn g
              JOIN distributors d ON d.id = g.distributor_id
              LEFT JOIN purchase_grn_item i ON i.grn_id = g.id
            """
                + where
                + """
             GROUP BY g.id, d.firm_name, g.invoice_number, g.invoice_date, g.status, g.created_at
             ORDER BY g.invoice_date DESC, g.created_at DESC
             LIMIT ? OFFSET ?
            """,
            (rs, n) ->
                new GrnListRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("firm_name"),
                    rs.getString("invoice_number"),
                    rs.getDate("invoice_date").toLocalDate(),
                    rs.getInt("line_count"),
                    rs.getLong("taxable_amount_paise"),
                    rs.getLong("gst_amount_paise"),
                    rs.getLong("total_paise"),
                    GrnStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant()),
            pageArgs.toArray());
    return new ListResult(rows, totalCount);
  }

  @Override
  public KpiRow kpi(UUID pharmacyId, LocalDate monthStart, LocalDate monthEndExclusive) {
    return jdbc.query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN g.invoice_date >= ? AND g.invoice_date < ? THEN 1 ELSE 0 END), 0)
            AS purchases_this_month,
          COALESCE(SUM(CASE WHEN g.invoice_date >= ? AND g.invoice_date < ?
            THEN i.gst_amount_paise ELSE 0 END), 0) AS input_gst_credit_this_month_paise,
          COUNT(DISTINCT g.id) AS total_grns
        FROM purchase_grn g
        LEFT JOIN purchase_grn_item i ON i.grn_id = g.id
        WHERE g.pharmacy_id = ? AND g.deleted_at IS NULL
        """,
        rs -> {
          rs.next();
          return new KpiRow(
              rs.getLong("purchases_this_month"),
              rs.getLong("input_gst_credit_this_month_paise"),
              rs.getLong("total_grns"));
        },
        Date.valueOf(monthStart),
        Date.valueOf(monthEndExclusive),
        Date.valueOf(monthStart),
        Date.valueOf(monthEndExclusive),
        pharmacyId);
  }

  @Override
  public PurchaseGrn updateStatus(
      UUID grnId, GrnStatus status, Instant stockedAt, UUID stockedBy, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE purchase_grn
           SET status = ?, stocked_at = ?, stocked_by = ?, updated_at = ?
         WHERE id = ?
        """,
        status.name(),
        stockedAt == null ? null : Timestamp.from(stockedAt),
        stockedBy,
        Timestamp.from(updatedAt),
        grnId);
    List<PurchaseGrn> rows = jdbc.query(SELECT_GRN + " WHERE id = ?", GRN_MAPPER, grnId);
    return rows.get(0);
  }

  @Override
  public PurchaseGrn updateImportUnmatched(
      UUID grnId, String importUnmatchedJson, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE purchase_grn
           SET import_unmatched = CAST(? AS jsonb), updated_at = ?
         WHERE id = ?
        """,
        importUnmatchedJson,
        Timestamp.from(updatedAt),
        grnId);
    List<PurchaseGrn> rows = jdbc.query(SELECT_GRN + " WHERE id = ?", GRN_MAPPER, grnId);
    return rows.get(0);
  }

  @Override
  public PurchaseGrnItem insertItem(PurchaseGrnItem item) {
    jdbc.update(
        """
        INSERT INTO purchase_grn_item (
          id, grn_id, pharmacy_id, product_id, batch_number, expiry_date, manufactured_date,
          quantity, free_quantity, purchase_price_paise, mrp_paise, gst_pct,
          taxable_amount_paise, gst_amount_paise, line_total_paise, is_new_product,
          created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        item.id(),
        item.grnId(),
        item.pharmacyId(),
        item.productId(),
        item.batchNumber(),
        Date.valueOf(item.expiryDate()),
        item.manufacturedDate() == null ? null : Date.valueOf(item.manufacturedDate()),
        item.quantity(),
        item.freeQuantity(),
        item.purchasePricePaise(),
        item.mrpPaise(),
        item.gstPct(),
        item.taxableAmountPaise(),
        item.gstAmountPaise(),
        item.lineTotalPaise(),
        item.newProduct(),
        Timestamp.from(item.createdAt()),
        Timestamp.from(item.updatedAt()));
    return item;
  }

  @Override
  public Optional<PurchaseGrnItem> findItem(UUID pharmacyId, UUID grnId, UUID itemId) {
    List<PurchaseGrnItem> rows =
        jdbc.query(
            SELECT_ITEM + " WHERE pharmacy_id = ? AND grn_id = ? AND id = ?",
            ITEM_MAPPER,
            pharmacyId,
            grnId,
            itemId);
    return rows.stream().findFirst();
  }

  @Override
  public PurchaseGrnItem updateItem(PurchaseGrnItem item) {
    jdbc.update(
        """
        UPDATE purchase_grn_item
           SET quantity = ?, free_quantity = ?, purchase_price_paise = ?, mrp_paise = ?,
               expiry_date = ?, gst_pct = ?, taxable_amount_paise = ?, gst_amount_paise = ?,
               line_total_paise = ?, updated_at = ?
         WHERE id = ? AND grn_id = ? AND pharmacy_id = ?
        """,
        item.quantity(),
        item.freeQuantity(),
        item.purchasePricePaise(),
        item.mrpPaise(),
        Date.valueOf(item.expiryDate()),
        item.gstPct(),
        item.taxableAmountPaise(),
        item.gstAmountPaise(),
        item.lineTotalPaise(),
        Timestamp.from(item.updatedAt()),
        item.id(),
        item.grnId(),
        item.pharmacyId());
    return item;
  }

  @Override
  public boolean deleteItem(UUID pharmacyId, UUID grnId, UUID itemId) {
    return jdbc.update(
            "DELETE FROM purchase_grn_item WHERE pharmacy_id = ? AND grn_id = ? AND id = ?",
            pharmacyId,
            grnId,
            itemId)
        > 0;
  }

  @Override
  public List<ItemWithProduct> listItems(UUID pharmacyId, UUID grnId) {
    return jdbc.query(
        """
        SELECT i.id, i.grn_id, i.pharmacy_id, i.product_id, i.batch_number, i.expiry_date,
               i.manufactured_date, i.quantity, i.free_quantity, i.purchase_price_paise,
               i.mrp_paise, i.gst_pct, i.taxable_amount_paise, i.gst_amount_paise,
               i.line_total_paise, i.is_new_product, i.created_at, i.updated_at, p.name AS product_name
          FROM purchase_grn_item i
          JOIN pharmacy_product p ON p.id = i.product_id
         WHERE i.pharmacy_id = ? AND i.grn_id = ?
         ORDER BY i.created_at ASC
        """,
        (rs, n) -> new ItemWithProduct(mapItem(rs), rs.getString("product_name")),
        pharmacyId,
        grnId);
  }

  @Override
  public int countItems(UUID pharmacyId, UUID grnId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM purchase_grn_item WHERE pharmacy_id = ? AND grn_id = ?",
            Integer.class,
            pharmacyId,
            grnId);
    return count == null ? 0 : count;
  }

  @Override
  public String distributorFirmName(UUID pharmacyId, UUID distributorId) {
    List<String> names =
        jdbc.query(
            "SELECT firm_name FROM distributors WHERE pharmacy_id = ? AND id = ?",
            (rs, i) -> rs.getString(1),
            pharmacyId,
            distributorId);
    return names.isEmpty() ? null : names.getFirst();
  }

  private static final RowMapper<PurchaseGrn> GRN_MAPPER = (rs, i) -> mapGrn(rs);
  private static final RowMapper<PurchaseGrnItem> ITEM_MAPPER = (rs, i) -> mapItem(rs);

  static PurchaseGrn mapGrn(ResultSet rs) throws SQLException {
    Timestamp stocked = rs.getTimestamp("stocked_at");
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new PurchaseGrn(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("distributor_id"),
        rs.getString("invoice_number"),
        rs.getDate("invoice_date").toLocalDate(),
        GrnStatus.valueOf(rs.getString("status")),
        stocked == null ? null : stocked.toInstant(),
        (UUID) rs.getObject("stocked_by"),
        (UUID) rs.getObject("created_by"),
        rs.getString("import_unmatched"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        deleted == null ? null : deleted.toInstant());
  }

  static PurchaseGrnItem mapItem(ResultSet rs) throws SQLException {
    Date mfg = rs.getDate("manufactured_date");
    return new PurchaseGrnItem(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("grn_id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("product_id"),
        rs.getString("batch_number"),
        rs.getDate("expiry_date").toLocalDate(),
        mfg == null ? null : mfg.toLocalDate(),
        rs.getInt("quantity"),
        rs.getInt("free_quantity"),
        rs.getLong("purchase_price_paise"),
        rs.getLong("mrp_paise"),
        rs.getInt("gst_pct"),
        rs.getLong("taxable_amount_paise"),
        rs.getLong("gst_amount_paise"),
        rs.getLong("line_total_paise"),
        rs.getBoolean("is_new_product"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
