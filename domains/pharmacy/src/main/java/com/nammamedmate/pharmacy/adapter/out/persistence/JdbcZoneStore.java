package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
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
}
