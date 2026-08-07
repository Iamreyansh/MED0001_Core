package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.ZonePharmacyLookupPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcZonePharmacyLookup implements ZonePharmacyLookupPort {

  private final JdbcTemplate jdbc;

  public JdbcZonePharmacyLookup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PharmacyRef> findById(UUID pharmacyId) {
    List<PharmacyRef> rows =
        jdbc.query(
            """
            SELECT id,
                   COALESCE(NULLIF(TRIM(business_name), ''), name) AS pharmacy_name,
                   zone_id, is_online,
                   COALESCE(admin_forced_offline, FALSE) AS admin_forced_offline,
                   status
            FROM pharmacies
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) ->
                new PharmacyRef(
                    (UUID) rs.getObject("id"),
                    rs.getString("pharmacy_name"),
                    (UUID) rs.getObject("zone_id"),
                    rs.getBoolean("is_online"),
                    rs.getBoolean("admin_forced_offline"),
                    rs.getString("status")),
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<UUID> zoneIdForPincode(String pincode) {
    if (pincode == null || pincode.isBlank()) {
      return Optional.empty();
    }
    List<UUID> rows =
        jdbc.query(
            "SELECT zone_id FROM pincode_zone_mapping WHERE pincode = ?",
            (rs, i) -> (UUID) rs.getObject("zone_id"),
            pincode.trim());
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
  }
}
