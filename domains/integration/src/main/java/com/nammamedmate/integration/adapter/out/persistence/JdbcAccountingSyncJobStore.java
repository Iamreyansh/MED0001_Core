package com.nammamedmate.integration.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.AccountingSyncJobStore;
import com.nammamedmate.integration.domain.AccountingSyncJob;
import com.nammamedmate.integration.domain.AccountingSyncStatuses;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAccountingSyncJobStore implements AccountingSyncJobStore {

  private static final TypeReference<List<Map<String, Object>>> ERRORS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcAccountingSyncJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void insert(AccountingSyncJob job) {
    jdbc.update(
        """
        INSERT INTO accounting_sync_jobs (
          id, pharmacy_id, accounting_system, sync_type, period_from, period_to,
          status, records_processed, records_synced, records_failed, errors,
          triggered_by, queued_at, started_at, completed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        """,
        job.id(),
        job.pharmacyId(),
        job.accountingSystem(),
        job.syncType(),
        Date.valueOf(job.periodFrom()),
        Date.valueOf(job.periodTo()),
        job.status(),
        job.recordsProcessed(),
        job.recordsSynced(),
        job.recordsFailed(),
        toJson(job.errors()),
        job.triggeredBy(),
        Timestamp.from(job.queuedAt()),
        ts(job.startedAt()),
        ts(job.completedAt()));
  }

  @Override
  public void update(AccountingSyncJob job) {
    jdbc.update(
        """
        UPDATE accounting_sync_jobs SET
          status = ?,
          records_processed = ?,
          records_synced = ?,
          records_failed = ?,
          errors = ?::jsonb,
          started_at = ?,
          completed_at = ?
        WHERE id = ?
        """,
        job.status(),
        job.recordsProcessed(),
        job.recordsSynced(),
        job.recordsFailed(),
        toJson(job.errors()),
        ts(job.startedAt()),
        ts(job.completedAt()),
        job.id());
  }

  @Override
  public Optional<AccountingSyncJob> findById(UUID id) {
    List<AccountingSyncJob> rows =
        jdbc.query("SELECT * FROM accounting_sync_jobs WHERE id = ?", this::mapRow, id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean hasActiveJob(UUID pharmacyId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM accounting_sync_jobs
             WHERE pharmacy_id = ?
               AND status IN (?, ?)
            """,
            Integer.class,
            pharmacyId,
            AccountingSyncStatuses.QUEUED,
            AccountingSyncStatuses.RUNNING);
    return count != null && count > 0;
  }

  @Override
  public List<AccountingSyncJob> findQueued(int limit) {
    return jdbc.query(
        """
        SELECT * FROM accounting_sync_jobs
         WHERE status = ?
         ORDER BY queued_at
         LIMIT ?
        """,
        this::mapRow,
        AccountingSyncStatuses.QUEUED,
        limit);
  }

  private AccountingSyncJob mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AccountingSyncJob(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("accounting_system"),
        rs.getString("sync_type"),
        rs.getDate("period_from").toLocalDate(),
        rs.getDate("period_to").toLocalDate(),
        rs.getString("status"),
        rs.getInt("records_processed"),
        rs.getInt("records_synced"),
        rs.getInt("records_failed"),
        readErrors(rs.getString("errors")),
        rs.getString("triggered_by"),
        rs.getTimestamp("queued_at").toInstant(),
        instant(rs.getTimestamp("started_at")),
        instant(rs.getTimestamp("completed_at")));
  }

  private String toJson(List<Map<String, Object>> errors) {
    try {
      // AccountingSyncJob normalises null → List.of() in its compact ctor.
      return mapper.writeValueAsString(errors);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialise sync errors", e);
    }
  }

  private List<Map<String, Object>> readErrors(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return List.of();
      }
      return mapper.readValue(raw, ERRORS);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse sync errors", e);
    }
  }

  private static Timestamp ts(java.time.Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static java.time.Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
