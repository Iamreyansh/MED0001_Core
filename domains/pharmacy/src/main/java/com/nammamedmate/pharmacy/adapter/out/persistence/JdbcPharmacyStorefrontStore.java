package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyStorefrontStore implements PharmacyStorefrontStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyStorefrontStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<StorefrontRow> findStorefront(UUID pharmacyId) {
    List<StorefrontRow> rows =
        jdbc.query(
            """
            SELECT p.id, p.status, p.is_online, p.admin_forced_offline, p.zone_id, z.name AS zone_name
            FROM pharmacies p
            LEFT JOIN zones z ON z.id = p.zone_id
            WHERE p.id = ? AND p.deleted_at IS NULL
            """,
            this::mapRow,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public void updateOnlineStatus(
      UUID pharmacyId, boolean isOnline, boolean adminForcedOffline, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET
          is_online = ?,
          admin_forced_offline = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        isOnline,
        adminForcedOffline,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void updateZone(UUID pharmacyId, UUID zoneId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET zone_id = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        zoneId,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  private StorefrontRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new StorefrontRow(
        (UUID) rs.getObject("id"),
        rs.getString("status"),
        rs.getBoolean("is_online"),
        rs.getBoolean("admin_forced_offline"),
        (UUID) rs.getObject("zone_id"),
        rs.getString("zone_name"));
  }
}
