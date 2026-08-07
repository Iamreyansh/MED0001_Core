package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcZoneStore implements ZoneStore {

  private final JdbcTemplate jdbc;

  public JdbcZoneStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ZoneRecord> findById(UUID id) {
    List<ZoneRecord> rows =
        jdbc.query(
            "SELECT id, name, active FROM zones WHERE id = ?",
            (rs, i) ->
                new ZoneRecord(
                    (UUID) rs.getObject("id"), rs.getString("name"), rs.getBoolean("active")),
            id);
    return rows.stream().findFirst();
  }

  @Override
  public List<AdminZoneRow> listForAdmin(String city, Boolean isActive) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT
              z.id,
              z.name,
              z.city,
              z.state,
              z.active,
              z.coverage_area_sqkm,
              z.created_at,
              COUNT(p.id) FILTER (
                WHERE p.deleted_at IS NULL AND p.status = 'ACTIVE'
              ) AS pharmacy_count,
              COUNT(p.id) FILTER (
                WHERE p.deleted_at IS NULL AND p.status = 'ACTIVE' AND p.is_online = TRUE
              ) AS online_pharmacy_count
            FROM zones z
            LEFT JOIN pharmacies p ON p.zone_id = z.id
            WHERE 1 = 1
            """);
    List<Object> args = new ArrayList<>();
    if (city != null && !city.isBlank()) {
      sql.append(" AND z.city ILIKE ? ");
      args.add(city.trim());
    }
    if (isActive != null) {
      sql.append(" AND z.active = ? ");
      args.add(isActive);
    }
    sql.append(
        """
         GROUP BY z.id, z.name, z.city, z.state, z.active, z.coverage_area_sqkm, z.created_at
         ORDER BY z.name ASC
        """);
    return jdbc.query(sql.toString(), this::mapAdminRow, args.toArray());
  }

  private AdminZoneRow mapAdminRow(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal area = rs.getBigDecimal("coverage_area_sqkm");
    Timestamp created = rs.getTimestamp("created_at");
    return new AdminZoneRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("city"),
        rs.getString("state"),
        rs.getBoolean("active"),
        rs.getInt("pharmacy_count"),
        rs.getInt("online_pharmacy_count"),
        area,
        created == null ? null : created.toInstant());
  }
}
