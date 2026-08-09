package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPharmacyProductStore implements PharmacyProductStore {

  private static final String SELECT_BASE =
      """
      SELECT p.id, p.pharmacy_id, p.master_medicine_id, p.name, p.salt_composition, p.manufacturer,
             p.pack_size, p.pack_unit, p.category_id, c.name AS category_name, p.form, p.schedule,
             p.hsn_code, p.gst_pct, p.mrp_paise, p.is_rx_only, p.is_loose_selling_enabled,
             p.is_online_visible, p.reorder_level, p.rack_locations, p.total_stock_units,
             p.total_batches, p.earliest_expiry, p.cost_value_paise, p.last_movement_at,
             p.product_photo_url, p.created_at, p.updated_at
        FROM pharmacy_product p
        LEFT JOIN medicine_category c ON c.id = p.category_id
      """;

  private final JdbcTemplate jdbc;

  public JdbcPharmacyProductStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ListResult list(ListFilter filter, Instant now) {
    Where where = buildWhere(filter, now);
    String orderBy = orderBy(filter.sort(), filter.order());
    int offset = (filter.page() - 1) * filter.limit();

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pharmacy_product p " + where.sql,
            Long.class,
            where.args.toArray());
    if (total == null) {
      total = 0L;
    }

    List<Object> pageArgs = new ArrayList<>(where.args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<PharmacyProduct> rows =
        jdbc.query(
            SELECT_BASE + where.sql + orderBy + " LIMIT ? OFFSET ?",
            ROW_MAPPER,
            pageArgs.toArray());

    Map<String, Long> tabCounts =
        tabCounts(filter.pharmacyId(), filter.q(), filter.categoryId(), now);
    return new ListResult(rows, total, tabCounts);
  }

  @Override
  public List<PharmacyProduct> listAllForExport(ListFilter filter, Instant now) {
    Where where = buildWhere(filter, now);
    String orderBy = orderBy(filter.sort(), filter.order());
    return jdbc.query(SELECT_BASE + where.sql + orderBy, ROW_MAPPER, where.args.toArray());
  }

  @Override
  public SummaryRow summary(UUID pharmacyId, Instant now) {
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate expiryCutoff = today.plusMonths(4);
    Instant deadCutoff = now.minus(90, java.time.temporal.ChronoUnit.DAYS);

    return jdbc.query(
        """
            SELECT
              COUNT(*) AS total_skus,
              COALESCE(SUM(total_stock_units), 0) AS total_units,
              COALESCE(SUM(cost_value_paise), 0) AS stock_value_at_cost_paise,
              COALESCE(SUM(mrp_paise * total_stock_units), 0) AS retail_value_mrp_paise,
              COALESCE(SUM(CASE WHEN reorder_level > 0 AND total_stock_units <= reorder_level THEN 1 ELSE 0 END), 0) AS low_stock_count,
              COALESCE(SUM(CASE WHEN earliest_expiry IS NOT NULL AND earliest_expiry <= ? THEN 1 ELSE 0 END), 0) AS expiring_count,
              COALESCE(SUM(CASE WHEN last_movement_at IS NULL OR last_movement_at < ? THEN 1 ELSE 0 END), 0) AS dead_stock_count,
              COALESCE(SUM(CASE WHEN total_stock_units = 0 THEN 1 ELSE 0 END), 0) AS out_of_stock_count,
              COALESCE(SUM(CASE WHEN rack_locations IS NULL OR cardinality(rack_locations) = 0 THEN 1 ELSE 0 END), 0) AS unallocated_count
            FROM pharmacy_product
            WHERE pharmacy_id = ? AND deleted_at IS NULL
            """,
        rs -> {
          rs.next();
          return new SummaryRow(
              rs.getLong("total_skus"),
              rs.getLong("total_units"),
              rs.getLong("stock_value_at_cost_paise"),
              rs.getLong("retail_value_mrp_paise"),
              rs.getLong("low_stock_count"),
              rs.getLong("expiring_count"),
              rs.getLong("dead_stock_count"),
              rs.getLong("out_of_stock_count"),
              rs.getLong("unallocated_count"));
        },
        Date.valueOf(expiryCutoff),
        Timestamp.from(deadCutoff),
        pharmacyId);
  }

  @Override
  public Optional<PharmacyProduct> findById(UUID pharmacyId, UUID productId) {
    List<PharmacyProduct> rows =
        jdbc.query(
            SELECT_BASE + " WHERE p.pharmacy_id = ? AND p.id = ? AND p.deleted_at IS NULL",
            ROW_MAPPER,
            pharmacyId,
            productId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<PharmacyProduct> findByNameAndManufacturer(
      UUID pharmacyId, String name, String manufacturer) {
    String mfr = manufacturer == null ? "" : manufacturer.trim();
    List<PharmacyProduct> rows =
        jdbc.query(
            SELECT_BASE
                + """
                 WHERE p.pharmacy_id = ? AND p.deleted_at IS NULL
                   AND LOWER(TRIM(p.name)) = LOWER(TRIM(?))
                   AND LOWER(TRIM(COALESCE(p.manufacturer, ''))) = LOWER(TRIM(?))
                """,
            ROW_MAPPER,
            pharmacyId,
            name,
            mfr);
    return rows.stream().findFirst();
  }

  @Override
  public List<PharmacyProduct> searchByName(UUID pharmacyId, String query, int limit) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    return jdbc.query(
        SELECT_BASE
            + """
             WHERE p.pharmacy_id = ? AND p.deleted_at IS NULL
               AND LOWER(p.name) LIKE ?
             ORDER BY p.name ASC
             LIMIT ?
            """,
        ROW_MAPPER,
        pharmacyId,
        "%" + query.trim().toLowerCase(Locale.ROOT) + "%",
        Math.max(1, Math.min(limit, 50)));
  }

  @Override
  public PharmacyProduct insert(PharmacyProduct product) {
    jdbc.update(
        """
        INSERT INTO pharmacy_product (
          id, pharmacy_id, master_medicine_id, name, salt_composition, manufacturer,
          pack_size, pack_unit, category_id, form, schedule, hsn_code, gst_pct, mrp_paise,
          is_rx_only, is_loose_selling_enabled, is_online_visible, reorder_level, rack_locations,
          total_stock_units, total_batches, earliest_expiry, cost_value_paise, last_movement_at,
          product_photo_url, created_at, updated_at, deleted_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)
        """,
        product.id(),
        product.pharmacyId(),
        product.masterMedicineId(),
        product.name(),
        product.saltComposition(),
        product.manufacturer(),
        product.packSize(),
        product.packUnit(),
        product.categoryId(),
        product.form(),
        product.schedule(),
        product.hsnCode(),
        product.gstPct() == null ? 12 : product.gstPct().intValueExact(),
        product.mrpPaise(),
        product.isRxOnly(),
        product.isLooseSellingEnabled(),
        product.isOnlineVisible(),
        product.reorderLevel(),
        toTextArray(product.rackLocations()),
        product.totalStockUnits(),
        product.totalBatches(),
        product.earliestExpiry() == null ? null : Date.valueOf(product.earliestExpiry()),
        product.costValuePaise(),
        product.lastMovementAt() == null ? null : Timestamp.from(product.lastMovementAt()),
        product.productPhotoUrl(),
        Timestamp.from(product.createdAt()),
        Timestamp.from(product.updatedAt()));
    return product;
  }

  @Override
  public void updateMrp(UUID pharmacyId, UUID productId, long mrpPaise, Instant now) {
    jdbc.update(
        """
        UPDATE pharmacy_product
           SET mrp_paise = ?, updated_at = ?
         WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
        """,
        mrpPaise,
        Timestamp.from(now),
        pharmacyId,
        productId);
  }

  @Override
  public Optional<PharmacyProduct> updateSettings(
      UUID pharmacyId, UUID productId, SettingsPatch patch, Instant now) {
    StringBuilder sql = new StringBuilder("UPDATE pharmacy_product SET updated_at = ?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(now));
    if (patch.isLooseSellingEnabled() != null) {
      sql.append(", is_loose_selling_enabled = ?");
      args.add(patch.isLooseSellingEnabled());
    }
    if (patch.isOnlineVisible() != null) {
      sql.append(", is_online_visible = ?");
      args.add(patch.isOnlineVisible());
    }
    if (patch.reorderLevel() != null) {
      sql.append(", reorder_level = ?");
      args.add(patch.reorderLevel());
    }
    if (patch.rackLocationCode() != null) {
      sql.append(", rack_locations = ?");
      args.add(toTextArray(List.of(patch.rackLocationCode())));
    }
    sql.append(" WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL");
    args.add(pharmacyId);
    args.add(productId);
    int updated = jdbc.update(sql.toString(), args.toArray());
    if (updated == 0) {
      return Optional.empty();
    }
    return findById(pharmacyId, productId);
  }

  @Override
  public Optional<PharmacyProduct> updateDetails(
      UUID pharmacyId, UUID productId, DetailsPatch patch, Instant now) {
    StringBuilder sql = new StringBuilder("UPDATE pharmacy_product SET updated_at = ?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(now));
    if (patch.name() != null) {
      sql.append(", name = ?");
      args.add(patch.name());
    }
    if (patch.saltComposition() != null) {
      sql.append(", salt_composition = ?");
      args.add(patch.saltComposition());
    }
    if (patch.manufacturer() != null) {
      sql.append(", manufacturer = ?");
      args.add(patch.manufacturer());
    }
    if (patch.packSize() != null) {
      sql.append(", pack_size = ?");
      args.add(patch.packSize());
    }
    if (patch.packUnit() != null) {
      sql.append(", pack_unit = ?");
      args.add(patch.packUnit());
    }
    if (patch.categoryId() != null) {
      sql.append(", category_id = ?");
      args.add(patch.categoryId());
    }
    if (patch.form() != null) {
      sql.append(", form = ?");
      args.add(patch.form());
    }
    if (patch.schedule() != null) {
      sql.append(", schedule = ?");
      args.add(patch.schedule());
    }
    if (patch.hsnCode() != null) {
      sql.append(", hsn_code = ?");
      args.add(patch.hsnCode());
    }
    if (patch.gstPct() != null) {
      sql.append(", gst_pct = ?");
      args.add(patch.gstPct().intValueExact());
    }
    if (patch.rackLocations() != null) {
      sql.append(", rack_locations = ?");
      args.add(toTextArray(patch.rackLocations()));
    }
    if (patch.productPhotoUrl() != null) {
      sql.append(", product_photo_url = ?");
      args.add(patch.productPhotoUrl());
    }
    sql.append(" WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL");
    args.add(pharmacyId);
    args.add(productId);
    int updated = jdbc.update(sql.toString(), args.toArray());
    if (updated == 0) {
      return Optional.empty();
    }
    return findById(pharmacyId, productId);
  }

  private Map<String, Long> tabCounts(UUID pharmacyId, String q, UUID categoryId, Instant now) {
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Date expiryCutoff = Date.valueOf(today.plusMonths(4));
    Timestamp deadCutoff = Timestamp.from(now.minus(90, java.time.temporal.ChronoUnit.DAYS));

    StringBuilder where = new StringBuilder(" WHERE pharmacy_id = ? AND deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    args.add(expiryCutoff);
    args.add(deadCutoff);
    args.add(expiryCutoff);
    args.add(pharmacyId);
    appendSearchAndCategory(where, args, q, categoryId);

    return jdbc.query(
        """
        SELECT
          COUNT(*) AS all_count,
          COALESCE(SUM(CASE WHEN (reorder_level > 0 AND total_stock_units <= reorder_level)
              OR (earliest_expiry IS NOT NULL AND earliest_expiry <= ?)
              OR (last_movement_at IS NULL OR last_movement_at < ?) THEN 1 ELSE 0 END), 0) AS alerts_count,
          COALESCE(SUM(CASE WHEN reorder_level > 0 AND total_stock_units <= reorder_level THEN 1 ELSE 0 END), 0) AS low_stock_count,
          COALESCE(SUM(CASE WHEN earliest_expiry IS NOT NULL AND earliest_expiry <= ? THEN 1 ELSE 0 END), 0) AS expiring_count,
          COALESCE(SUM(CASE WHEN is_rx_only THEN 1 ELSE 0 END), 0) AS rx_only_count,
          COALESCE(SUM(CASE WHEN total_stock_units = 0 THEN 1 ELSE 0 END), 0) AS out_of_stock_count,
          COALESCE(SUM(CASE WHEN rack_locations IS NULL OR cardinality(rack_locations) = 0 THEN 1 ELSE 0 END), 0) AS unallocated_count
        FROM pharmacy_product
        """
            + where,
        rs -> {
          rs.next();
          Map<String, Long> m = new LinkedHashMap<>();
          m.put("ALL", rs.getLong("all_count"));
          m.put("ALERTS", rs.getLong("alerts_count"));
          m.put("LOW_STOCK", rs.getLong("low_stock_count"));
          m.put("EXPIRING", rs.getLong("expiring_count"));
          m.put("RX_ONLY", rs.getLong("rx_only_count"));
          m.put("OUT_OF_STOCK", rs.getLong("out_of_stock_count"));
          m.put("UNALLOCATED", rs.getLong("unallocated_count"));
          return m;
        },
        args.toArray());
  }

  private Where buildWhere(ListFilter filter, Instant now) {
    StringBuilder sql = new StringBuilder(" WHERE p.pharmacy_id = ? AND p.deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    args.add(filter.pharmacyId());
    appendSearchAndCategory(sql, args, filter.q(), filter.categoryId(), "p.");

    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate expiryCutoff = today.plusMonths(4);
    Instant deadCutoff = now.minus(90, java.time.temporal.ChronoUnit.DAYS);
    String tab = filter.tab() == null ? "ALL" : filter.tab().toUpperCase(Locale.ROOT);
    switch (tab) {
      case "LOW_STOCK" ->
          sql.append(" AND p.reorder_level > 0 AND p.total_stock_units <= p.reorder_level ");
      case "EXPIRING" -> {
        sql.append(" AND p.earliest_expiry IS NOT NULL AND p.earliest_expiry <= ? ");
        args.add(Date.valueOf(expiryCutoff));
      }
      case "RX_ONLY" -> sql.append(" AND p.is_rx_only = TRUE ");
      case "OUT_OF_STOCK" -> sql.append(" AND p.total_stock_units = 0 ");
      case "UNALLOCATED" ->
          sql.append(" AND (p.rack_locations IS NULL OR cardinality(p.rack_locations) = 0) ");
      case "ALERTS" -> {
        sql.append(
            """
             AND (
               (p.reorder_level > 0 AND p.total_stock_units <= p.reorder_level)
               OR (p.earliest_expiry IS NOT NULL AND p.earliest_expiry <= ?)
               OR (p.last_movement_at IS NULL OR p.last_movement_at < ?)
             )
            """);
        args.add(Date.valueOf(expiryCutoff));
        args.add(Timestamp.from(deadCutoff));
      }
      default -> {
        // ALL
      }
    }
    return new Where(sql.toString(), args);
  }

  private static void appendSearchAndCategory(
      StringBuilder sql, List<Object> args, String q, UUID categoryId) {
    appendSearchAndCategory(sql, args, q, categoryId, "");
  }

  private static void appendSearchAndCategory(
      StringBuilder sql, List<Object> args, String q, UUID categoryId, String prefix) {
    if (q != null && !q.isBlank()) {
      String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
      sql.append(" AND (")
          .append("LOWER(")
          .append(prefix)
          .append("name) LIKE ? OR LOWER(COALESCE(")
          .append(prefix)
          .append("salt_composition,'')) LIKE ? OR LOWER(COALESCE(")
          .append(prefix)
          .append("manufacturer,'')) LIKE ? OR EXISTS (")
          .append("SELECT 1 FROM unnest(COALESCE(")
          .append(prefix)
          .append("rack_locations, ARRAY[]::text[])) AS rack WHERE LOWER(rack) LIKE ?)")
          .append(") ");
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    if (categoryId != null) {
      sql.append(" AND ").append(prefix).append("category_id = ? ");
      args.add(categoryId);
    }
  }

  private static String orderBy(String sort, String order) {
    String dir = "desc".equalsIgnoreCase(order) ? "DESC" : "ASC";
    String col =
        switch (sort == null ? "name" : sort.toLowerCase(Locale.ROOT)) {
          case "stock" -> "p.total_stock_units";
          case "value" -> "p.cost_value_paise";
          case "expiry" -> "p.earliest_expiry";
          default -> "p.name";
        };
    return " ORDER BY " + col + " " + dir + " NULLS LAST, p.id ASC ";
  }

  private Object toTextArray(List<String> values) {
    return jdbc.execute(
        (java.sql.Connection conn) -> {
          if (values.isEmpty()) {
            return conn.createArrayOf("text", new String[0]);
          }
          return conn.createArrayOf("text", values.toArray());
        });
  }

  private static final RowMapper<PharmacyProduct> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

  static PharmacyProduct mapRow(ResultSet rs) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    Timestamp moved = rs.getTimestamp("last_movement_at");
    Date expiry = rs.getDate("earliest_expiry");
    BigDecimal gst = rs.getBigDecimal("gst_pct");
    if (gst == null) {
      gst = BigDecimal.valueOf(rs.getInt("gst_pct"));
    }
    return new PharmacyProduct(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("master_medicine_id"),
        rs.getString("name"),
        rs.getString("salt_composition"),
        rs.getString("manufacturer"),
        rs.getInt("pack_size"),
        rs.getString("pack_unit"),
        (UUID) rs.getObject("category_id"),
        rs.getString("category_name"),
        rs.getString("form"),
        rs.getString("schedule"),
        rs.getString("hsn_code"),
        gst,
        rs.getLong("mrp_paise"),
        rs.getBoolean("is_rx_only"),
        rs.getBoolean("is_loose_selling_enabled"),
        rs.getBoolean("is_online_visible"),
        rs.getInt("reorder_level"),
        readTextArray(rs.getArray("rack_locations")),
        rs.getInt("total_stock_units"),
        rs.getInt("total_batches"),
        expiry == null ? null : expiry.toLocalDate(),
        rs.getLong("cost_value_paise"),
        moved == null ? null : moved.toInstant(),
        rs.getString("product_photo_url"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }

  static List<String> readTextArray(Array array) throws SQLException {
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

  private record Where(String sql, List<Object> args) {}
}
