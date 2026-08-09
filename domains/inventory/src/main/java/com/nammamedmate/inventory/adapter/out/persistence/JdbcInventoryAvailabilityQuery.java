package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Online availability from {@code pharmacy_product} (+ medicine_master for banned / names). Only
 * products with {@code is_online_visible} and a {@code master_medicine_id} participate.
 */
@Repository
public class JdbcInventoryAvailabilityQuery implements InventoryAvailabilityQuery {

  private final JdbcTemplate jdbc;

  public JdbcInventoryAvailabilityQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean stocksMedicine(UUID pharmacyId, UUID medicineId) {
    List<Integer> qty =
        jdbc.query(
            """
            SELECT pp.total_stock_units
              FROM pharmacy_product pp
              JOIN medicine_master mm ON mm.id = pp.master_medicine_id
             WHERE pp.pharmacy_id = ?
               AND pp.master_medicine_id = ?
               AND pp.deleted_at IS NULL
               AND pp.is_online_visible = TRUE
               AND mm.is_banned = FALSE
            """,
            (rs, i) -> rs.getInt(1),
            pharmacyId,
            medicineId);
    return !qty.isEmpty() && qty.getFirst() > 0;
  }

  @Override
  public List<StockLine> checkAvailability(UUID pharmacyId, List<UUID> medicineIds) {
    if (medicineIds == null || medicineIds.isEmpty()) {
      return List.of();
    }
    Map<UUID, StockLine> byId = new LinkedHashMap<>();
    for (UUID id : medicineIds) {
      if (id != null) {
        byId.put(id, null);
      }
    }
    if (byId.isEmpty()) {
      return List.of();
    }
    StringBuilder placeholders = new StringBuilder();
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    for (UUID id : byId.keySet()) {
      if (!placeholders.isEmpty()) {
        placeholders.append(',');
      }
      placeholders.append('?');
      args.add(id);
    }
    String sql =
        """
        SELECT mm.id, mm.name, mm.mrp_paise, mm.is_banned,
               pp.total_stock_units, pp.mrp_paise AS pharmacy_mrp_paise
          FROM medicine_master mm
          LEFT JOIN pharmacy_product pp
            ON pp.master_medicine_id = mm.id
           AND pp.pharmacy_id = ?
           AND pp.deleted_at IS NULL
           AND pp.is_online_visible = TRUE
         WHERE mm.id IN (
        """
            + placeholders
            + ")";
    jdbc.query(
        sql,
        (RowCallbackHandler)
            rs -> {
              UUID id = (UUID) rs.getObject("id");
              boolean banned = rs.getBoolean("is_banned");
              Integer stock = (Integer) rs.getObject("total_stock_units");
              Long pharmacyMrp = (Long) rs.getObject("pharmacy_mrp_paise");
              long mrp = rs.getLong("mrp_paise");
              String name = rs.getString("name");
              long price = pharmacyMrp == null ? mrp : pharmacyMrp;
              if (banned) {
                byId.put(id, new StockLine(id, name, 0, 0, mrp, false, "BANNED"));
              } else if (stock == null) {
                byId.put(id, new StockLine(id, name, 0, 0, mrp, false, "NOT_MAPPED"));
              } else if (stock <= 0) {
                byId.put(id, new StockLine(id, name, 0, price, mrp, false, "OUT_OF_STOCK"));
              } else {
                byId.put(id, new StockLine(id, name, stock, price, mrp, true, null));
              }
            },
        args.toArray());
    List<StockLine> out = new ArrayList<>();
    for (Map.Entry<UUID, StockLine> e : byId.entrySet()) {
      if (e.getValue() != null) {
        out.add(e.getValue());
      } else {
        out.add(
            new StockLine(
                e.getKey(),
                medicineName(e.getKey()).orElse("Unknown"),
                0,
                0,
                0,
                false,
                "NOT_FOUND"));
      }
    }
    return out;
  }

  @Override
  public ProductPage listVisibleProducts(
      UUID pharmacyId, String category, String search, int page, int limit) {
    int p = Math.max(page, 1);
    int lim = Math.min(Math.max(limit, 1), 100);
    StringBuilder where =
        new StringBuilder(
            """
            WHERE pp.pharmacy_id = ?
              AND pp.is_online_visible = TRUE
              AND pp.deleted_at IS NULL
              AND pp.total_stock_units > 0
              AND pp.master_medicine_id IS NOT NULL
              AND mm.is_banned = FALSE
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (category != null && !category.isBlank()) {
      where.append(" AND c.name ILIKE ? ");
      args.add(category.trim());
    }
    if (search != null && !search.isBlank()) {
      where.append(" AND pp.name ILIKE ? ");
      args.add("%" + search.trim() + "%");
    }
    Integer total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM pharmacy_product pp
              JOIN medicine_master mm ON mm.id = pp.master_medicine_id
              LEFT JOIN medicine_category c ON c.id = COALESCE(pp.category_id, mm.category_id)
            """
                + where,
            Integer.class,
            args.toArray());
    long totalCount = total == null ? 0 : total;
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(lim);
    pageArgs.add((p - 1) * lim);
    List<ProductRow> items =
        jdbc.query(
            """
            SELECT pp.master_medicine_id AS id, pp.name, pp.manufacturer,
                   COALESCE(c.name, '') AS category_name,
                   pp.pack_size, pp.pack_unit, pp.mrp_paise, pp.is_rx_only,
                   pp.total_stock_units, pp.product_photo_url
              FROM pharmacy_product pp
              JOIN medicine_master mm ON mm.id = pp.master_medicine_id
              LEFT JOIN medicine_category c ON c.id = COALESCE(pp.category_id, mm.category_id)
            """
                + where
                + " ORDER BY pp.name ASC LIMIT ? OFFSET ?",
            this::mapProduct,
            pageArgs.toArray());
    return new ProductPage(items, totalCount, p, lim);
  }

  @Override
  public Optional<MedicineDetails> findMedicine(UUID medicineId) {
    List<MedicineDetails> rows =
        jdbc.query(
            """
            SELECT mm.id, mm.name, mm.manufacturer, mm.pack_size, mm.pack_unit,
                   mm.is_rx_only, mm.is_banned
              FROM medicine_master mm
             WHERE mm.id = ?
            """,
            (rs, i) -> {
              java.math.BigDecimal packSize = rs.getBigDecimal("pack_size");
              String packUnit = rs.getString("pack_unit");
              String pack =
                  packSize == null
                      ? packUnit
                      : packSize.stripTrailingZeros().toPlainString()
                          + " "
                          + packUnit.toLowerCase(Locale.ROOT);
              return new MedicineDetails(
                  (UUID) rs.getObject("id"),
                  rs.getString("name"),
                  rs.getString("manufacturer"),
                  pack,
                  rs.getBoolean("is_rx_only"),
                  null,
                  rs.getBoolean("is_banned"));
            },
            medicineId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<String> medicineName(UUID medicineId) {
    return findMedicine(medicineId).map(MedicineDetails::name);
  }

  private ProductRow mapProduct(ResultSet rs, int rowNum) throws SQLException {
    int packSize = rs.getInt("pack_size");
    String packUnit = rs.getString("pack_unit");
    String pack = packSize + " " + (packUnit == null ? "" : packUnit.toLowerCase(Locale.ROOT));
    long mrp = rs.getLong("mrp_paise");
    return new ProductRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("manufacturer"),
        rs.getString("category_name"),
        pack.trim(),
        mrp,
        mrp,
        rs.getBoolean("is_rx_only"),
        rs.getInt("total_stock_units"),
        rs.getString("product_photo_url"));
  }
}
