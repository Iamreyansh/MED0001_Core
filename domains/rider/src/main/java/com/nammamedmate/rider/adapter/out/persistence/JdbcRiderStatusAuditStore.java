package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderStatusAuditStore implements RiderStatusAuditStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderStatusAuditStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(AuditRecord record) {
    jdbc.update(
        """
        INSERT INTO rider_status_audit_log (
          id, rider_id, changed_by, changed_by_role, from_status, to_status, reason, created_at
        ) VALUES (?,?,?,?,?,?,?,?)
        """,
        record.id(),
        record.riderId(),
        record.changedBy(),
        record.changedByRole(),
        record.fromStatus(),
        record.toStatus(),
        record.reason(),
        Timestamp.from(record.createdAt()));
  }

  @Override
  public Optional<AuditRecord> findLatestForceChange(UUID riderId) {
    List<AuditRecord> rows =
        jdbc.query(
            """
            SELECT * FROM rider_status_audit_log
            WHERE rider_id = ?
              AND changed_by_role IN ('admin_operations', 'admin_super')
              AND reason IS NOT NULL
            ORDER BY created_at DESC
            LIMIT 1
            """,
            this::map,
            riderId);
    return rows.stream().findFirst();
  }

  private AuditRecord map(ResultSet rs, int rowNum) throws SQLException {
    return new AuditRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rider_id"),
        (UUID) rs.getObject("changed_by"),
        rs.getString("changed_by_role"),
        rs.getString("from_status"),
        rs.getString("to_status"),
        rs.getString("reason"),
        rs.getTimestamp("created_at").toInstant());
  }
}
