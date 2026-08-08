package com.nammamedmate.settings.adapter.out.persistence;

import com.nammamedmate.settings.application.port.out.FeatureFlagStore;
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
public class JdbcFeatureFlagStore implements FeatureFlagStore {

  private static final String SELECT =
      """
      SELECT f.id, f.name, f.description, f.environment, f.enabled, f.rollout_percentage,
             f.notes, f.updated_by, s.name AS updated_by_name, f.created_at, f.updated_at
      FROM feature_flags f
      LEFT JOIN admin_staff s ON s.id = f.updated_by
      """;

  private final JdbcTemplate jdbc;

  public JdbcFeatureFlagStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<FeatureFlagRow> listByEnvironment(String environment) {
    return jdbc.query(
        SELECT + " WHERE f.environment = ? ORDER BY f.name ASC",
        (rs, i) -> mapRow(rs),
        environment);
  }

  @Override
  public Optional<FeatureFlagRow> findByNameAndEnvironment(String name, String environment) {
    List<FeatureFlagRow> rows =
        jdbc.query(
            SELECT + " WHERE f.name = ? AND f.environment = ?",
            (rs, i) -> mapRow(rs),
            name,
            environment);
    return rows.stream().findFirst();
  }

  @Override
  public FeatureFlagRow update(
      UUID id,
      boolean enabled,
      int rolloutPercentage,
      String notes,
      UUID updatedBy,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE feature_flags
        SET enabled = ?, rollout_percentage = ?, notes = ?, updated_by = ?, updated_at = ?
        WHERE id = ?
        """,
        enabled,
        rolloutPercentage,
        notes,
        updatedBy,
        Timestamp.from(updatedAt),
        id);
    return findById(id)
        .orElseThrow(() -> new IllegalStateException("feature flag missing after update: " + id));
  }

  @Override
  public List<FeatureFlagRow> listAll() {
    return jdbc.query(SELECT + " ORDER BY f.environment ASC, f.name ASC", (rs, i) -> mapRow(rs));
  }

  @Override
  public List<EnvCounts> countByEnvironment() {
    return jdbc.query(
        """
        SELECT environment,
               COUNT(*) AS total,
               COUNT(*) FILTER (WHERE enabled = TRUE) AS enabled
        FROM feature_flags
        GROUP BY environment
        ORDER BY environment
        """,
        (rs, i) ->
            new EnvCounts(rs.getString("environment"), rs.getLong("total"), rs.getLong("enabled")));
  }

  private Optional<FeatureFlagRow> findById(UUID id) {
    List<FeatureFlagRow> rows = jdbc.query(SELECT + " WHERE f.id = ?", (rs, i) -> mapRow(rs), id);
    return rows.stream().findFirst();
  }

  private static FeatureFlagRow mapRow(ResultSet rs) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    return new FeatureFlagRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("environment"),
        rs.getBoolean("enabled"),
        rs.getInt("rollout_percentage"),
        rs.getString("notes"),
        (UUID) rs.getObject("updated_by"),
        rs.getString("updated_by_name"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }
}
