package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.MedicineBanJobStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMedicineBanJobStore implements MedicineBanJobStore {

  private final JdbcTemplate jdbc;

  public JdbcMedicineBanJobStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insertQueued(
      UUID id, UUID medicineId, String reason, UUID initiatedBy, Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO medicine_ban_job
          (id, medicine_id, status, mappings_hidden, reason, initiated_by, created_at)
        VALUES (?, ?, 'QUEUED', 0, ?, ?, ?)
        """,
        id,
        medicineId,
        reason,
        initiatedBy,
        Timestamp.from(createdAt));
  }

  @Override
  public void markRunning(UUID id, Instant startedAt) {
    jdbc.update(
        """
        UPDATE medicine_ban_job
        SET status = 'RUNNING', started_at = ?
        WHERE id = ?
        """,
        Timestamp.from(startedAt),
        id);
  }

  @Override
  public void markCompleted(UUID id, int mappingsHidden, Instant completedAt) {
    jdbc.update(
        """
        UPDATE medicine_ban_job
        SET status = 'COMPLETED', mappings_hidden = ?, completed_at = ?
        WHERE id = ?
        """,
        mappingsHidden,
        Timestamp.from(completedAt),
        id);
  }

  @Override
  public Optional<BanJobRow> findById(UUID id) {
    List<BanJobRow> rows =
        jdbc.query(
            """
            SELECT id, medicine_id, status, mappings_hidden, reason, initiated_by,
                   created_at, started_at, completed_at
            FROM medicine_ban_job WHERE id = ?
            """,
            (rs, i) ->
                new BanJobRow(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("medicine_id"),
                    rs.getString("status"),
                    rs.getInt("mappings_hidden"),
                    rs.getString("reason"),
                    (UUID) rs.getObject("initiated_by"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("started_at") == null
                        ? null
                        : rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("completed_at") == null
                        ? null
                        : rs.getTimestamp("completed_at").toInstant()),
            id);
    return rows.stream().findFirst();
  }
}
