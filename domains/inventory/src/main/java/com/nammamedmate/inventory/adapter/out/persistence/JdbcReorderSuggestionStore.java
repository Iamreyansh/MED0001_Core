package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore;
import com.nammamedmate.inventory.domain.ReorderSuggestionSnapshot;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReorderSuggestionStore implements ReorderSuggestionStore {

  private final JdbcTemplate jdbc;

  public JdbcReorderSuggestionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int replaceSnapshots(
      UUID pharmacyId, LocalDate snapshotDate, List<ReorderSuggestionSnapshot> rows) {
    jdbc.update(
        "DELETE FROM reorder_suggestion_snapshot WHERE pharmacy_id = ? AND snapshot_date = ?",
        pharmacyId,
        Date.valueOf(snapshotDate));
    if (rows == null || rows.isEmpty()) {
      return 0;
    }
    for (ReorderSuggestionSnapshot row : rows) {
      jdbc.update(
          """
          INSERT INTO reorder_suggestion_snapshot (
            id, pharmacy_id, product_id, current_stock, reorder_level, days_of_cover,
            best_distributor_id, landed_price_paise, snapshot_date, created_at
          ) VALUES (?,?,?,?,?,?,?,?,?,?)
          """,
          row.id(),
          row.pharmacyId(),
          row.productId(),
          row.currentStock(),
          row.reorderLevel(),
          row.daysOfCover(),
          row.bestDistributorId(),
          row.landedPricePaise(),
          Date.valueOf(row.snapshotDate()),
          Timestamp.from(row.createdAt()));
    }
    return rows.size();
  }

  @Override
  public List<LowStockProduct> listLowStockProducts(UUID pharmacyId) {
    return jdbc.query(
        """
        SELECT id, name, manufacturer, total_stock_units, reorder_level, mrp_paise, gst_pct
          FROM pharmacy_product
         WHERE pharmacy_id = ?
           AND deleted_at IS NULL
           AND reorder_level > 0
           AND total_stock_units <= reorder_level
         ORDER BY name
        """,
        (rs, i) ->
            new LowStockProduct(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                rs.getString("manufacturer"),
                rs.getInt("total_stock_units"),
                rs.getInt("reorder_level"),
                rs.getLong("mrp_paise"),
                rs.getInt("gst_pct")),
        pharmacyId);
  }

  @Override
  public List<SupplyOffer> listActiveOffers(UUID pharmacyId, UUID productId) {
    return jdbc.query(
        """
        SELECT d.id AS distributor_id, d.firm_name, d.phone,
               s.purchase_price_paise, s.scheme_description
          FROM distributor_supply_item s
          JOIN distributors d ON d.id = s.distributor_id
         WHERE s.pharmacy_id = ?
           AND s.product_id = ?
           AND d.deleted_at IS NULL
           AND d.is_active = TRUE
         ORDER BY s.purchase_price_paise ASC
        """,
        (rs, i) ->
            new SupplyOffer(
                (UUID) rs.getObject("distributor_id"),
                rs.getString("firm_name"),
                rs.getString("phone"),
                rs.getLong("purchase_price_paise"),
                rs.getString("scheme_description")),
        pharmacyId,
        productId);
  }

  @Override
  public Optional<LocalDate> latestSnapshotDate(UUID pharmacyId) {
    List<LocalDate> dates =
        jdbc.query(
            """
            SELECT MAX(snapshot_date) AS d
              FROM reorder_suggestion_snapshot
             WHERE pharmacy_id = ?
            """,
            (rs, i) -> {
              Date d = rs.getDate("d");
              return d == null ? null : d.toLocalDate();
            },
            pharmacyId);
    return dates.stream().filter(d -> d != null).findFirst();
  }

  @Override
  public ListResult listLatest(UUID pharmacyId, int page, int limit) {
    Optional<LocalDate> date = latestSnapshotDate(pharmacyId);
    if (date.isEmpty()) {
      return new ListResult(List.of(), 0);
    }
    long total = countForDate(pharmacyId, date.get());
    int offset = Math.max(0, (page - 1) * limit);
    List<SuggestionRow> rows =
        jdbc.query(
            """
            SELECT s.id, s.pharmacy_id, s.product_id, s.current_stock, s.reorder_level,
                   s.days_of_cover, s.best_distributor_id, s.landed_price_paise,
                   s.snapshot_date, s.created_at,
                   p.name AS product_name, p.manufacturer,
                   d.firm_name AS best_distributor_name, d.phone AS best_distributor_phone
              FROM reorder_suggestion_snapshot s
              JOIN pharmacy_product p ON p.id = s.product_id
              LEFT JOIN distributors d ON d.id = s.best_distributor_id
             WHERE s.pharmacy_id = ? AND s.snapshot_date = ?
             ORDER BY p.name
             LIMIT ? OFFSET ?
            """,
            SUGGESTION_MAPPER,
            pharmacyId,
            Date.valueOf(date.get()),
            limit,
            offset);
    return new ListResult(rows, total);
  }

  @Override
  public long countLatest(UUID pharmacyId) {
    return latestSnapshotDate(pharmacyId).map(d -> countForDate(pharmacyId, d)).orElse(0L);
  }

  @Override
  public Optional<Instant> latestRefreshedAt(UUID pharmacyId) {
    List<Instant> times =
        jdbc.query(
            """
            SELECT MAX(created_at) AS t
              FROM reorder_suggestion_snapshot
             WHERE pharmacy_id = ?
            """,
            (rs, i) -> {
              Timestamp t = rs.getTimestamp("t");
              return t == null ? null : t.toInstant();
            },
            pharmacyId);
    return times.stream().filter(t -> t != null).findFirst();
  }

  @Override
  public List<UUID> listPharmacyIdsWithLowStock() {
    return jdbc.query(
        """
        SELECT DISTINCT pharmacy_id
          FROM pharmacy_product
         WHERE deleted_at IS NULL
           AND reorder_level > 0
           AND total_stock_units <= reorder_level
        """,
        (rs, i) -> (UUID) rs.getObject("pharmacy_id"));
  }

  private long countForDate(UUID pharmacyId, LocalDate date) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM reorder_suggestion_snapshot
             WHERE pharmacy_id = ? AND snapshot_date = ?
            """,
            Long.class,
            pharmacyId,
            Date.valueOf(date));
    return n == null ? 0L : n;
  }

  private static final RowMapper<SuggestionRow> SUGGESTION_MAPPER =
      (rs, i) -> {
        ReorderSuggestionSnapshot snap = mapSnapshot(rs);
        return new SuggestionRow(
            snap,
            rs.getString("product_name"),
            rs.getString("manufacturer"),
            rs.getString("best_distributor_name"),
            rs.getString("best_distributor_phone"));
      };

  private static ReorderSuggestionSnapshot mapSnapshot(ResultSet rs) throws SQLException {
    BigDecimal cover = rs.getBigDecimal("days_of_cover");
    Object landed = rs.getObject("landed_price_paise");
    return new ReorderSuggestionSnapshot(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("product_id"),
        rs.getInt("current_stock"),
        rs.getInt("reorder_level"),
        cover,
        (UUID) rs.getObject("best_distributor_id"),
        landed == null ? null : rs.getLong("landed_price_paise"),
        rs.getDate("snapshot_date").toLocalDate(),
        rs.getTimestamp("created_at").toInstant());
  }
}
