package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.RackLocationStore;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.RackLocation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRackLocationStore implements RackLocationStore {

  private static final RowMapper<RackLocation> RACK_MAPPER = (rs, n) -> mapRack(rs);

  private final JdbcTemplate jdbc;

  public JdbcRackLocationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RackLocation> findByCode(UUID pharmacyId, String rackCode) {
    List<RackLocation> rows =
        jdbc.query(
            """
            SELECT id, pharmacy_id, rack_code, zone_name, description,
                   created_at, updated_at, deleted_at
              FROM rack_location
             WHERE pharmacy_id = ? AND rack_code = ? AND deleted_at IS NULL
            """,
            RACK_MAPPER,
            pharmacyId,
            rackCode);
    return rows.stream().findFirst();
  }

  @Override
  public ListResult list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE r.pharmacy_id = ? AND r.deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    args.add(filter.pharmacyId());
    if (filter.zone() != null && !filter.zone().isBlank()) {
      where.append(" AND LOWER(r.zone_name) = LOWER(?) ");
      args.add(filter.zone().trim());
    }
    if (filter.q() != null && !filter.q().isBlank()) {
      where.append(" AND LOWER(r.rack_code) LIKE ? ");
      args.add("%" + filter.q().trim().toLowerCase() + "%");
    }

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rack_location r " + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;

    int offset = Math.max(0, (filter.page() - 1) * filter.limit());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);

    List<RackLocation> racks =
        jdbc.query(
            """
            SELECT r.id, r.pharmacy_id, r.rack_code, r.zone_name, r.description,
                   r.created_at, r.updated_at, r.deleted_at
              FROM rack_location r
            """
                + where
                + " ORDER BY r.rack_code ASC LIMIT ? OFFSET ?",
            RACK_MAPPER,
            pageArgs.toArray());

    List<ListRow> rows = new ArrayList<>(racks.size());
    for (RackLocation rack : racks) {
      long count = medicineCount(filter.pharmacyId(), rack.rackCode());
      List<PharmacyProduct> preview =
          medicinesInRackLimited(filter.pharmacyId(), rack.rackCode(), 2);
      rows.add(new ListRow(rack, count, preview));
    }
    return new ListResult(rows, totalCount);
  }

  @Override
  public Kpi kpi(UUID pharmacyId) {
    Long racks =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rack_location WHERE pharmacy_id = ? AND deleted_at IS NULL",
            Long.class,
            pharmacyId);
    Long zones =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT zone_name)
              FROM rack_location
             WHERE pharmacy_id = ? AND deleted_at IS NULL
            """,
            Long.class,
            pharmacyId);
    Long mapped =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_product
             WHERE pharmacy_id = ? AND deleted_at IS NULL
               AND rack_locations IS NOT NULL AND cardinality(rack_locations) > 0
            """,
            Long.class,
            pharmacyId);
    Long unlocated =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_product
             WHERE pharmacy_id = ? AND deleted_at IS NULL
               AND (rack_locations IS NULL OR cardinality(rack_locations) = 0)
            """,
            Long.class,
            pharmacyId);
    return new Kpi(
        racks == null ? 0 : racks,
        zones == null ? 0 : zones,
        mapped == null ? 0 : mapped,
        unlocated == null ? 0 : unlocated);
  }

  @Override
  public RackLocation insert(RackLocation rack) {
    jdbc.update(
        """
        INSERT INTO rack_location
          (id, pharmacy_id, rack_code, zone_name, description, created_at, updated_at, deleted_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
        """,
        rack.id(),
        rack.pharmacyId(),
        rack.rackCode(),
        rack.zoneName(),
        rack.description(),
        Timestamp.from(rack.createdAt()),
        Timestamp.from(rack.updatedAt()));
    return rack;
  }

  @Override
  public Optional<RackLocation> softDelete(UUID pharmacyId, String rackCode, Instant now) {
    Optional<RackLocation> existing = findByCode(pharmacyId, rackCode);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    int updated =
        jdbc.update(
            """
            UPDATE rack_location
               SET deleted_at = ?, updated_at = ?
             WHERE pharmacy_id = ? AND rack_code = ? AND deleted_at IS NULL
            """,
            Timestamp.from(now),
            Timestamp.from(now),
            pharmacyId,
            rackCode);
    if (updated == 0) {
      return Optional.empty();
    }
    RackLocation r = existing.get();
    return Optional.of(
        new RackLocation(
            r.id(),
            r.pharmacyId(),
            r.rackCode(),
            r.zoneName(),
            r.description(),
            r.createdAt(),
            now,
            now));
  }

  @Override
  public List<PharmacyProduct> medicinesInRack(UUID pharmacyId, String rackCode) {
    return medicinesInRackLimited(pharmacyId, rackCode, Integer.MAX_VALUE);
  }

  private List<PharmacyProduct> medicinesInRackLimited(
      UUID pharmacyId, String rackCode, int limit) {
    return jdbc.query(
        """
        SELECT p.id, p.pharmacy_id, p.master_medicine_id, p.name, p.salt_composition,
               p.manufacturer, p.pack_size, p.pack_unit, p.category_id, c.name AS category_name,
               p.form, p.schedule, p.hsn_code, p.gst_pct, p.mrp_paise, p.is_rx_only,
               p.is_loose_selling_enabled, p.is_online_visible, p.reorder_level, p.rack_locations,
               p.total_stock_units, p.total_batches, p.earliest_expiry, p.cost_value_paise,
               p.last_movement_at, p.product_photo_url, p.created_at, p.updated_at
          FROM pharmacy_product p
          LEFT JOIN medicine_category c ON c.id = p.category_id
         WHERE p.pharmacy_id = ? AND p.deleted_at IS NULL
           AND ? = ANY(p.rack_locations)
         ORDER BY p.name ASC
         LIMIT ?
        """,
        (rs, n) -> JdbcPharmacyProductStore.mapRow(rs),
        pharmacyId,
        rackCode,
        limit);
  }

  @Override
  public List<ProductPreview> blockingProducts(UUID pharmacyId, String rackCode, int limit) {
    return jdbc.query(
        """
        SELECT id, name FROM pharmacy_product
         WHERE pharmacy_id = ? AND deleted_at IS NULL
           AND ? = ANY(rack_locations)
         ORDER BY name ASC
         LIMIT ?
        """,
        (rs, n) -> new ProductPreview((UUID) rs.getObject("id"), rs.getString("name")),
        pharmacyId,
        rackCode,
        limit);
  }

  @Override
  public long medicineCount(UUID pharmacyId, String rackCode) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_product
             WHERE pharmacy_id = ? AND deleted_at IS NULL
               AND ? = ANY(rack_locations)
            """,
            Long.class,
            pharmacyId,
            rackCode);
    return count == null ? 0L : count;
  }

  @Override
  public List<RackLocation> findByCodes(UUID pharmacyId, List<String> rackCodes) {
    if (rackCodes == null || rackCodes.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", Collections.nCopies(rackCodes.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    args.addAll(rackCodes);
    return jdbc.query(
        """
        SELECT id, pharmacy_id, rack_code, zone_name, description,
               created_at, updated_at, deleted_at
          FROM rack_location
         WHERE pharmacy_id = ? AND deleted_at IS NULL
           AND rack_code IN ("""
            + placeholders
            + ")",
        RACK_MAPPER,
        args.toArray());
  }

  @Override
  public UnlocatedPage unlocated(UUID pharmacyId, int page, int limit) {
    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_product
             WHERE pharmacy_id = ? AND deleted_at IS NULL
               AND (rack_locations IS NULL OR cardinality(rack_locations) = 0)
            """,
            Long.class,
            pharmacyId);
    int offset = Math.max(0, (page - 1) * limit);
    List<PharmacyProduct> products =
        jdbc.query(
            """
            SELECT p.id, p.pharmacy_id, p.master_medicine_id, p.name, p.salt_composition,
                   p.manufacturer, p.pack_size, p.pack_unit, p.category_id, c.name AS category_name,
                   p.form, p.schedule, p.hsn_code, p.gst_pct, p.mrp_paise, p.is_rx_only,
                   p.is_loose_selling_enabled, p.is_online_visible, p.reorder_level, p.rack_locations,
                   p.total_stock_units, p.total_batches, p.earliest_expiry, p.cost_value_paise,
                   p.last_movement_at, p.product_photo_url, p.created_at, p.updated_at
              FROM pharmacy_product p
              LEFT JOIN medicine_category c ON c.id = p.category_id
             WHERE p.pharmacy_id = ? AND p.deleted_at IS NULL
               AND (p.rack_locations IS NULL OR cardinality(p.rack_locations) = 0)
             ORDER BY p.name ASC
             LIMIT ? OFFSET ?
            """,
            (rs, n) -> JdbcPharmacyProductStore.mapRow(rs),
            pharmacyId,
            limit,
            offset);
    return new UnlocatedPage(products, total == null ? 0L : total);
  }

  @Override
  public List<UUID> assignRack(
      UUID pharmacyId, List<UUID> productIds, String rackCode, Instant now) {
    List<UUID> assigned = new ArrayList<>();
    for (UUID productId : productIds) {
      Optional<PharmacyProduct> before = findProduct(pharmacyId, productId);
      if (before.isEmpty()) {
        continue;
      }
      if (before.get().rackLocations().contains(rackCode)) {
        continue;
      }
      int updated =
          jdbc.update(
              """
              UPDATE pharmacy_product
                 SET rack_locations = array_append(COALESCE(rack_locations, ARRAY[]::text[]), ?),
                     updated_at = ?
               WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
                 AND NOT (? = ANY(COALESCE(rack_locations, ARRAY[]::text[])))
              """,
              rackCode,
              Timestamp.from(now),
              pharmacyId,
              productId,
              rackCode);
      if (updated > 0) {
        assigned.add(productId);
      }
    }
    return assigned;
  }

  @Override
  public Optional<PharmacyProduct> addRackToProduct(
      UUID pharmacyId, UUID productId, String rackCode, Instant now) {
    Optional<PharmacyProduct> existing = findProduct(pharmacyId, productId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    if (existing.get().rackLocations().contains(rackCode)) {
      return existing;
    }
    jdbc.update(
        """
        UPDATE pharmacy_product
           SET rack_locations = array_append(COALESCE(rack_locations, ARRAY[]::text[]), ?),
               updated_at = ?
         WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
        """,
        rackCode,
        Timestamp.from(now),
        pharmacyId,
        productId);
    return findProduct(pharmacyId, productId);
  }

  @Override
  public Optional<PharmacyProduct> removeRackFromProduct(
      UUID pharmacyId, UUID productId, String rackCode, Instant now) {
    Optional<PharmacyProduct> existing = findProduct(pharmacyId, productId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    jdbc.update(
        """
        UPDATE pharmacy_product
           SET rack_locations = array_remove(COALESCE(rack_locations, ARRAY[]::text[]), ?),
               updated_at = ?
         WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
        """,
        rackCode,
        Timestamp.from(now),
        pharmacyId,
        productId);
    return findProduct(pharmacyId, productId);
  }

  private Optional<PharmacyProduct> findProduct(UUID pharmacyId, UUID productId) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT p.id, p.pharmacy_id, p.master_medicine_id, p.name, p.salt_composition,
                     p.manufacturer, p.pack_size, p.pack_unit, p.category_id, c.name AS category_name,
                     p.form, p.schedule, p.hsn_code, p.gst_pct, p.mrp_paise, p.is_rx_only,
                     p.is_loose_selling_enabled, p.is_online_visible, p.reorder_level, p.rack_locations,
                     p.total_stock_units, p.total_batches, p.earliest_expiry, p.cost_value_paise,
                     p.last_movement_at, p.product_photo_url, p.created_at, p.updated_at
                FROM pharmacy_product p
                LEFT JOIN medicine_category c ON c.id = p.category_id
               WHERE p.pharmacy_id = ? AND p.id = ? AND p.deleted_at IS NULL
              """,
              (rs, n) -> JdbcPharmacyProductStore.mapRow(rs),
              pharmacyId,
              productId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  static RackLocation mapRack(ResultSet rs) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new RackLocation(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("rack_code"),
        rs.getString("zone_name"),
        rs.getString("description"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant(),
        deleted == null ? null : deleted.toInstant());
  }
}
