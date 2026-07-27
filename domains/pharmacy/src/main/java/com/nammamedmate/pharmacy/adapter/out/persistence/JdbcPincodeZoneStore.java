package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PincodeZoneStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPincodeZoneStore implements PincodeZoneStore {

  private final JdbcTemplate jdbc;

  public JdbcPincodeZoneStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UUID> findZoneIdByPincode(String pincode) {
    if (pincode == null || pincode.isBlank()) {
      return Optional.empty();
    }
    List<UUID> rows =
        jdbc.query(
            "SELECT zone_id FROM pincode_zone_mapping WHERE pincode = ?",
            (rs, n) -> (UUID) rs.getObject("zone_id"),
            pincode.trim());
    return rows.stream().findFirst();
  }
}
