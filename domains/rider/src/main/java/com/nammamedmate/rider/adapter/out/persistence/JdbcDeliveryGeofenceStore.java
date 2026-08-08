package com.nammamedmate.rider.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.rider.application.port.out.DeliveryGeofenceStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeliveryGeofenceStore implements DeliveryGeofenceStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcDeliveryGeofenceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void insert(
      UUID id,
      UUID zoneId,
      String wkt,
      String coordinatesJson,
      BigDecimal areaSqKm,
      UUID createdBy,
      Instant now) {
    Timestamp ts = Timestamp.from(now);
    jdbc.update(
        """
        INSERT INTO delivery_geofences (
          id, zone_id, polygon, polygon_coordinates, area_sq_km, created_by, created_at, updated_at
        ) VALUES (
          ?, ?, ST_GeogFromText(?), ?::jsonb, ?, ?, ?, ?
        )
        """,
        id,
        zoneId,
        wkt,
        coordinatesJson,
        areaSqKm,
        createdBy,
        ts,
        ts);
  }

  @Override
  public boolean existsForZone(UUID zoneId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(1) FROM delivery_geofences WHERE zone_id = ?", Integer.class, zoneId);
    if (n == null) {
      return false;
    }
    return n > 0;
  }

  @Override
  public Optional<GeofenceRecord> findByZoneId(UUID zoneId) {
    List<GeofenceRecord> rows =
        jdbc.query(
            """
            SELECT g.id, g.zone_id, z.name AS zone_name, g.polygon_coordinates::text AS coords,
                   g.area_sq_km, g.created_by, g.created_at, g.updated_at
            FROM delivery_geofences g
            JOIN zones z ON z.id = g.zone_id
            WHERE g.zone_id = ?
            """,
            this::map,
            zoneId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean containsPoint(UUID zoneId, double lat, double lng) {
    Boolean inside =
        jdbc.queryForObject(
            """
            SELECT ST_Covers(
              polygon,
              ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
            )
            FROM delivery_geofences
            WHERE zone_id = ?
            """,
            Boolean.class,
            lng,
            lat,
            zoneId);
    return Boolean.TRUE.equals(inside);
  }

  private GeofenceRecord map(ResultSet rs, int rowNum) throws SQLException {
    List<List<Double>> coords;
    try {
      coords = mapper.readValue(rs.getString("coords"), new TypeReference<>() {});
    } catch (Exception e) {
      coords = List.of();
    }
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    return new GeofenceRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("zone_id"),
        rs.getString("zone_name"),
        coords,
        rs.getBigDecimal("area_sq_km"),
        (UUID) rs.getObject("created_by"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }
}
