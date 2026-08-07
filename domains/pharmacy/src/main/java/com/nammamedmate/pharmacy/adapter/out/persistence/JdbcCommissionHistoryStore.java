package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCommissionHistoryStore implements CommissionHistoryStore {

  private final JdbcTemplate jdbc;

  public JdbcCommissionHistoryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CommissionHistoryRow> findPendingChange(UUID pharmacyId) {
    List<CommissionHistoryRow> rows =
        jdbc.query(
            """
            SELECT * FROM commission_history
            WHERE pharmacy_id = ? AND applied_at IS NULL AND deleted_at IS NULL
            ORDER BY effective_from ASC
            LIMIT 1
            """,
            this::mapRow,
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public void insert(CommissionHistoryRow row) {
    jdbc.update(
        """
        INSERT INTO commission_history (
          id, pharmacy_id, previous_commission_pct, new_commission_pct,
          effective_from, reason, notes, changed_by, changed_at, applied_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.previousCommissionPct(),
        row.newCommissionPct(),
        row.effectiveFrom(),
        row.reason(),
        row.notes(),
        row.changedBy(),
        Timestamp.from(row.changedAt()),
        row.appliedAt() == null ? null : Timestamp.from(row.appliedAt()));
  }

  @Override
  public List<CommissionHistoryRow> findDueForApply(LocalDate effectiveDate) {
    return jdbc.query(
        """
        SELECT * FROM commission_history
        WHERE effective_from = ? AND applied_at IS NULL AND deleted_at IS NULL
        """,
        this::mapRow,
        effectiveDate);
  }

  @Override
  public void markApplied(UUID id, Instant appliedAt) {
    jdbc.update(
        """
        UPDATE commission_history SET applied_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(appliedAt),
        id);
  }

  private CommissionHistoryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp applied = rs.getTimestamp("applied_at");
    return new CommissionHistoryRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getBigDecimal("previous_commission_pct"),
        rs.getBigDecimal("new_commission_pct"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("reason"),
        rs.getString("notes"),
        (UUID) rs.getObject("changed_by"),
        rs.getTimestamp("changed_at").toInstant(),
        applied == null ? null : applied.toInstant());
  }
}
