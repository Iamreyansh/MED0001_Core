package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeliveryZoneStore implements DeliveryZoneStore {

  private final JdbcTemplate jdbc;

  public JdbcDeliveryZoneStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ZoneRow> findById(UUID id) {
    List<ZoneRow> rows =
        jdbc.query(
            """
            SELECT id, name, city, state, polygon_geojson::text AS polygon_geojson, area_sq_km,
                   base_fee, per_km_fee, sla_minutes, min_order_value, free_delivery_threshold,
                   surge_multiplier, is_surge_active, is_serviceable, offline_reason, active,
                   created_by, created_at, updated_at
            FROM zones
            WHERE id = ? AND deleted_at IS NULL
            """,
            this::mapRow,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public List<ZoneRow> listPricing() {
    return jdbc.query(
        """
        SELECT id, name, city, state, polygon_geojson::text AS polygon_geojson, area_sq_km,
               base_fee, per_km_fee, sla_minutes, min_order_value, free_delivery_threshold,
               surge_multiplier, is_surge_active, is_serviceable, offline_reason, active,
               created_by, created_at, updated_at
        FROM zones
        WHERE deleted_at IS NULL
        ORDER BY city, name
        """,
        this::mapRow);
  }

  @Override
  public Optional<ZoneRow> findContaining(double lat, double lng) {
    List<ZoneRow> rows =
        jdbc.query(
            """
            SELECT id, name, city, state, polygon_geojson::text AS polygon_geojson, area_sq_km,
                   base_fee, per_km_fee, sla_minutes, min_order_value, free_delivery_threshold,
                   surge_multiplier, is_surge_active, is_serviceable, offline_reason, active,
                   created_by, created_at, updated_at
            FROM zones
            WHERE deleted_at IS NULL
              AND polygon IS NOT NULL
              AND ST_Covers(
                polygon,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
              )
            ORDER BY is_serviceable DESC, name
            LIMIT 1
            """,
            this::mapRow,
            lng,
            lat);
    return rows.stream().findFirst();
  }

  @Override
  public boolean existsNameInCity(String name, String city, UUID excludeId) {
    Integer n =
        excludeId == null
            ? jdbc.queryForObject(
                """
                SELECT COUNT(1) FROM zones
                WHERE LOWER(name) = LOWER(?) AND LOWER(city) = LOWER(?) AND deleted_at IS NULL
                """,
                Integer.class,
                name,
                city)
            : jdbc.queryForObject(
                """
                SELECT COUNT(1) FROM zones
                WHERE LOWER(name) = LOWER(?) AND LOWER(city) = LOWER(?)
                  AND id <> ? AND deleted_at IS NULL
                """,
                Integer.class,
                name,
                city,
                excludeId);
    return n != null && n > 0;
  }

  @Override
  public List<ZoneSummaryRow> list(String city, Boolean serviceable, int offset, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT z.id, z.name, z.city, z.base_fee, z.sla_minutes, z.surge_multiplier,
                   z.is_surge_active, z.is_serviceable,
                   COUNT(p.id) FILTER (WHERE p.deleted_at IS NULL) AS pharmacies_count
            FROM zones z
            LEFT JOIN pharmacies p ON p.zone_id = z.id
            WHERE z.deleted_at IS NULL
            """);
    List<Object> args = new ArrayList<>();
    if (city != null && !city.isBlank()) {
      sql.append(" AND z.city ILIKE ? ");
      args.add(city.trim());
    }
    if (serviceable != null) {
      sql.append(" AND z.is_serviceable = ? ");
      args.add(serviceable);
    }
    sql.append(
        """
         GROUP BY z.id, z.name, z.city, z.base_fee, z.sla_minutes, z.surge_multiplier,
                  z.is_surge_active, z.is_serviceable
         ORDER BY z.name ASC
         LIMIT ? OFFSET ?
        """);
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::mapSummary, args.toArray());
  }

  @Override
  public int count(String city, Boolean serviceable) {
    StringBuilder sql =
        new StringBuilder("SELECT COUNT(1) FROM zones z WHERE z.deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    if (city != null && !city.isBlank()) {
      sql.append(" AND z.city ILIKE ? ");
      args.add(city.trim());
    }
    if (serviceable != null) {
      sql.append(" AND z.is_serviceable = ? ");
      args.add(serviceable);
    }
    Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    return n == null ? 0 : n;
  }

  @Override
  public void insert(
      UUID id,
      String name,
      String city,
      String state,
      String wkt,
      String polygonGeoJson,
      BigDecimal areaSqKm,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      int slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      BigDecimal surgeMultiplier,
      boolean serviceable,
      UUID createdBy,
      Instant now) {
    Timestamp ts = Timestamp.from(now);
    jdbc.update(
        """
        INSERT INTO zones (
          id, name, city, state, active, coverage_area_sqkm, created_at,
          polygon, polygon_geojson, area_sq_km, base_fee, per_km_fee, sla_minutes,
          min_order_value, free_delivery_threshold, surge_multiplier, is_surge_active,
          is_serviceable, created_by, updated_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?,
          ST_GeogFromText(?), ?::jsonb, ?, ?, ?, ?,
          ?, ?, ?, FALSE,
          ?, ?, ?
        )
        """,
        id,
        name,
        city,
        state,
        serviceable,
        areaSqKm,
        ts,
        wkt,
        polygonGeoJson,
        areaSqKm,
        baseFee,
        perKmFee,
        slaMinutes,
        minOrderValue,
        freeDeliveryThreshold,
        surgeMultiplier,
        serviceable,
        createdBy,
        ts);
  }

  @Override
  public void updateFields(
      UUID id,
      Integer slaMinutes,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      String name,
      String wkt,
      String polygonGeoJson,
      BigDecimal areaSqKm,
      Instant now) {
    Timestamp ts = Timestamp.from(now);
    if (wkt != null) {
      jdbc.update(
          """
          UPDATE zones SET
            sla_minutes = COALESCE(?, sla_minutes),
            base_fee = COALESCE(?, base_fee),
            per_km_fee = COALESCE(?, per_km_fee),
            min_order_value = COALESCE(?, min_order_value),
            free_delivery_threshold = COALESCE(?, free_delivery_threshold),
            name = COALESCE(?, name),
            polygon = ST_GeogFromText(?),
            polygon_geojson = ?::jsonb,
            area_sq_km = ?,
            coverage_area_sqkm = ?,
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          slaMinutes,
          baseFee,
          perKmFee,
          minOrderValue,
          freeDeliveryThreshold,
          name,
          wkt,
          polygonGeoJson,
          areaSqKm,
          areaSqKm,
          ts,
          id);
      return;
    }
    jdbc.update(
        """
        UPDATE zones SET
          sla_minutes = COALESCE(?, sla_minutes),
          base_fee = COALESCE(?, base_fee),
          per_km_fee = COALESCE(?, per_km_fee),
          min_order_value = COALESCE(?, min_order_value),
          free_delivery_threshold = COALESCE(?, free_delivery_threshold),
          name = COALESCE(?, name),
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        slaMinutes,
        baseFee,
        perKmFee,
        minOrderValue,
        freeDeliveryThreshold,
        name,
        ts,
        id);
  }

  @Override
  public void updateSurge(UUID id, boolean surgeActive, BigDecimal surgeMultiplier, Instant now) {
    jdbc.update(
        """
        UPDATE zones SET is_surge_active = ?, surge_multiplier = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        surgeActive,
        surgeMultiplier,
        Timestamp.from(now),
        id);
  }

  @Override
  public void updateServiceable(UUID id, boolean serviceable, String reason, Instant now) {
    jdbc.update(
        """
        UPDATE zones SET
          is_serviceable = ?, active = ?, offline_reason = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        serviceable,
        serviceable,
        reason,
        Timestamp.from(now),
        id);
  }

  @Override
  public int countServiceable() {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(1) FROM zones WHERE deleted_at IS NULL AND is_serviceable = TRUE",
            Integer.class);
    return n == null ? 0 : n;
  }

  @Override
  public int countOnlineRiders(UUID zoneId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM riders
            WHERE deleted_at IS NULL
              AND status IN ('ONLINE', 'ON_TRIP')
              AND COALESCE(current_zone_id, primary_zone_id) = ?
            """,
            Integer.class,
            zoneId);
    return n == null ? 0 : n;
  }

  @Override
  public int countOnlineRidersAll() {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM riders
            WHERE deleted_at IS NULL AND status IN ('ONLINE', 'ON_TRIP')
            """,
            Integer.class);
    return n == null ? 0 : n;
  }

  @Override
  public int countPharmacies(UUID zoneId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM pharmacies
            WHERE zone_id = ? AND deleted_at IS NULL
            """,
            Integer.class,
            zoneId);
    return n == null ? 0 : n;
  }

  @Override
  public List<DemandHour> demandVsSupply(UUID zoneId, Instant from, Instant to) {
    return jdbc.query(
        """
        WITH hours AS (
          SELECT generate_series(?::timestamptz, ?::timestamptz, interval '1 hour') AS hour
        ),
        ord AS (
          SELECT date_trunc('hour', o.created_at) AS hour, COUNT(1) AS orders
          FROM orders o
          JOIN pharmacies p ON p.id = o.pharmacy_id
          WHERE p.zone_id = ?
            AND o.deleted_at IS NULL
            AND o.created_at >= ? AND o.created_at < ?
          GROUP BY 1
        ),
        supply AS (
          SELECT date_trunc('hour', a.created_at) AS hour,
                 COUNT(DISTINCT a.rider_id) AS online_riders
          FROM rider_status_audit_log a
          WHERE a.to_status IN ('ONLINE', 'ON_TRIP')
            AND a.created_at >= ? AND a.created_at < ?
            AND EXISTS (
              SELECT 1 FROM riders r
              WHERE r.id = a.rider_id
                AND COALESCE(r.current_zone_id, r.primary_zone_id) = ?
            )
          GROUP BY 1
        )
        SELECT h.hour,
               COALESCE(ord.orders, 0) AS orders,
               COALESCE(supply.online_riders, 0) AS online_riders
        FROM hours h
        LEFT JOIN ord ON ord.hour = h.hour
        LEFT JOIN supply ON supply.hour = h.hour
        ORDER BY h.hour ASC
        """,
        (rs, i) ->
            new DemandHour(
                rs.getTimestamp("hour").toInstant(),
                rs.getInt("orders"),
                rs.getInt("online_riders")),
        Timestamp.from(from),
        Timestamp.from(to),
        zoneId,
        Timestamp.from(from),
        Timestamp.from(to),
        Timestamp.from(from),
        Timestamp.from(to),
        zoneId);
  }

  @Override
  public BigDecimal avgDeliveryMinutes(UUID zoneId) {
    BigDecimal v =
        jdbc.queryForObject(
            """
            SELECT AVG(EXTRACT(EPOCH FROM (o.updated_at - o.created_at)) / 60.0)
            FROM orders o
            JOIN pharmacies p ON p.id = o.pharmacy_id
            WHERE p.zone_id = ?
              AND o.deleted_at IS NULL
              AND o.status = 'DELIVERED'
              AND o.updated_at >= NOW() - INTERVAL '7 days'
            """,
            BigDecimal.class,
            zoneId);
    return v;
  }

  @Override
  public BigDecimal avgDeliveryMinutesAll() {
    return jdbc.queryForObject(
        """
        SELECT AVG(EXTRACT(EPOCH FROM (o.updated_at - o.created_at)) / 60.0)
        FROM orders o
        WHERE o.deleted_at IS NULL
          AND o.status = 'DELIVERED'
          AND o.updated_at >= NOW() - INTERVAL '7 days'
        """,
        BigDecimal.class);
  }

  @Override
  public boolean isPharmacyAddressServiceable(UUID pharmacyId, double lat, double lng) {
    Boolean ok =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pharmacies p
              JOIN zones z ON z.id = p.zone_id
              WHERE p.id = ?
                AND p.deleted_at IS NULL
                AND z.deleted_at IS NULL
                AND z.is_serviceable = TRUE
                AND z.polygon IS NOT NULL
                AND ST_Covers(
                  z.polygon,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                )
            )
            """,
            Boolean.class,
            pharmacyId,
            lng,
            lat);
    return Boolean.TRUE.equals(ok);
  }

  @Override
  public Optional<BigDecimal> minOrderValueForPharmacyAddress(
      UUID pharmacyId, double lat, double lng) {
    List<BigDecimal> rows =
        jdbc.query(
            """
            SELECT z.min_order_value
            FROM pharmacies p
            JOIN zones z ON z.id = p.zone_id
            WHERE p.id = ?
              AND p.deleted_at IS NULL
              AND z.deleted_at IS NULL
              AND z.is_serviceable = TRUE
              AND z.polygon IS NOT NULL
              AND ST_Covers(
                z.polygon,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
              )
            LIMIT 1
            """,
            (rs, i) -> rs.getBigDecimal("min_order_value"),
            pharmacyId,
            lng,
            lat);
    return rows.stream().findFirst();
  }

  private ZoneRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ZoneRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("city"),
        rs.getString("state"),
        rs.getString("polygon_geojson"),
        rs.getBigDecimal("area_sq_km"),
        rs.getBigDecimal("base_fee"),
        rs.getBigDecimal("per_km_fee"),
        rs.getInt("sla_minutes"),
        rs.getBigDecimal("min_order_value"),
        rs.getBigDecimal("free_delivery_threshold"),
        rs.getBigDecimal("surge_multiplier"),
        rs.getBoolean("is_surge_active"),
        rs.getBoolean("is_serviceable"),
        rs.getString("offline_reason"),
        rs.getBoolean("active"),
        (UUID) rs.getObject("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private ZoneSummaryRow mapSummary(ResultSet rs, int rowNum) throws SQLException {
    return new ZoneSummaryRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("city"),
        rs.getBigDecimal("base_fee"),
        rs.getInt("sla_minutes"),
        rs.getBigDecimal("surge_multiplier"),
        rs.getBoolean("is_surge_active"),
        rs.getBoolean("is_serviceable"),
        rs.getInt("pharmacies_count"));
  }
}
