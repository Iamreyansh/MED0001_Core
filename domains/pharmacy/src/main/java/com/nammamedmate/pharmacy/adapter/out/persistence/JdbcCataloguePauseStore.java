package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.CataloguePauseStore;
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
public class JdbcCataloguePauseStore implements CataloguePauseStore {

  private final JdbcTemplate jdbc;

  public JdbcCataloguePauseStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CataloguePauseRow> findActivePause(UUID pharmacyId) {
    List<CataloguePauseRow> rows =
        jdbc.query(
            """
            SELECT id, pharmacy_id, reason, paused_at, auto_resume_at, resumed_at,
                   items_hidden_count, paused_by
            FROM catalogue_pause
            WHERE pharmacy_id = ? AND resumed_at IS NULL
            ORDER BY paused_at DESC
            LIMIT 1
            """,
            this::mapRow,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public void insert(CataloguePauseRow row) {
    jdbc.update(
        """
        INSERT INTO catalogue_pause (
          id, pharmacy_id, reason, paused_at, auto_resume_at, items_hidden_count, paused_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.reason(),
        Timestamp.from(row.pausedAt()),
        Timestamp.from(row.autoResumeAt()),
        row.itemsHiddenCount(),
        row.pausedBy());
  }

  @Override
  public void markResumed(UUID id, Instant resumedAt) {
    jdbc.update(
        "UPDATE catalogue_pause SET resumed_at = ? WHERE id = ?", Timestamp.from(resumedAt), id);
  }

  @Override
  public List<CataloguePauseRow> findDueForResume(Instant asOf) {
    return jdbc.query(
        """
        SELECT id, pharmacy_id, reason, paused_at, auto_resume_at, resumed_at,
               items_hidden_count, paused_by
        FROM catalogue_pause
        WHERE resumed_at IS NULL AND auto_resume_at <= ?
        ORDER BY auto_resume_at ASC
        """,
        this::mapRow,
        Timestamp.from(asOf));
  }

  private CataloguePauseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new CataloguePauseRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("reason"),
        ts(rs, "paused_at"),
        ts(rs, "auto_resume_at"),
        ts(rs, "resumed_at"),
        rs.getInt("items_hidden_count"),
        (UUID) rs.getObject("paused_by"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
