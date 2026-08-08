package com.nammamedmate.settings.adapter.out.persistence;

import com.nammamedmate.settings.application.port.out.PlatformConfigStore;
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
public class JdbcPlatformConfigStore implements PlatformConfigStore {

  private static final String SELECT =
      """
      SELECT key, value, type, unit, domain, immutable, description, updated_by, updated_at
      FROM platform_config
      """;

  private final JdbcTemplate jdbc;

  public JdbcPlatformConfigStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ConfigRow> listAll() {
    return jdbc.query(SELECT + " ORDER BY domain ASC, key ASC", (rs, i) -> mapRow(rs));
  }

  @Override
  public List<ConfigRow> listByDomain(String domain) {
    return jdbc.query(SELECT + " WHERE domain = ? ORDER BY key ASC", (rs, i) -> mapRow(rs), domain);
  }

  @Override
  public Optional<ConfigRow> findByKey(String key) {
    List<ConfigRow> rows = jdbc.query(SELECT + " WHERE key = ?", (rs, i) -> mapRow(rs), key);
    return rows.stream().findFirst();
  }

  @Override
  public void updateValue(String key, String value, UUID updatedBy, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE platform_config
        SET value = ?, updated_by = ?, updated_at = ?
        WHERE key = ?
        """,
        value,
        updatedBy,
        Timestamp.from(updatedAt),
        key);
  }

  @Override
  public void insertHistory(
      UUID id,
      String key,
      String oldValue,
      String newValue,
      UUID changedBy,
      Instant changedAt,
      String notes) {
    jdbc.update(
        """
        INSERT INTO config_history (id, key, old_value, new_value, changed_by, changed_at, notes)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        key,
        oldValue,
        newValue,
        changedBy,
        Timestamp.from(changedAt),
        notes);
  }

  @Override
  public List<HistoryRow> listHistory(String key) {
    return jdbc.query(
        """
        SELECT h.id, h.key, h.old_value, h.new_value, h.changed_by,
               s.name AS changed_by_name, h.changed_at, h.notes
        FROM config_history h
        LEFT JOIN admin_staff s ON s.id = h.changed_by
        WHERE h.key = ?
        ORDER BY h.changed_at DESC
        """,
        (rs, i) -> mapHistory(rs),
        key);
  }

  private static ConfigRow mapRow(ResultSet rs) throws SQLException {
    Timestamp updated = rs.getTimestamp("updated_at");
    return new ConfigRow(
        rs.getString("key"),
        rs.getString("value"),
        rs.getString("type"),
        rs.getString("unit"),
        rs.getString("domain"),
        rs.getBoolean("immutable"),
        rs.getString("description"),
        (UUID) rs.getObject("updated_by"),
        updated == null ? null : updated.toInstant());
  }

  private static HistoryRow mapHistory(ResultSet rs) throws SQLException {
    Timestamp changed = rs.getTimestamp("changed_at");
    return new HistoryRow(
        (UUID) rs.getObject("id"),
        rs.getString("key"),
        rs.getString("old_value"),
        rs.getString("new_value"),
        (UUID) rs.getObject("changed_by"),
        rs.getString("changed_by_name"),
        changed == null ? null : changed.toInstant(),
        rs.getString("notes"));
  }
}
