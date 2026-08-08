package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcZoneLookupAdapter implements ZoneLookupPort {

  private final JdbcTemplate jdbc;

  public JdbcZoneLookupAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ZoneInfo> findById(UUID zoneId) {
    List<ZoneInfo> rows =
        jdbc.query(
            "SELECT id, name, active FROM zones WHERE id = ?",
            (rs, i) ->
                new ZoneInfo(
                    (UUID) rs.getObject("id"), rs.getString("name"), rs.getBoolean("active")),
            zoneId);
    return rows.stream().findFirst();
  }
}
