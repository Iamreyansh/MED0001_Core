package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcBulkActionJobStore implements BulkActionJobStore {

  private static final TypeReference<List<Map<String, Object>>> SKIPPED_TYPE =
      new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcBulkActionJobStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(JobRow row) {
    jdbc.update(
        """
        INSERT INTO bulk_action_job (
            id, action, payload, pharmacy_ids, status, total_pharmacies,
            processed, succeeded, failed, skipped, skipped_pharmacies, result_payload,
            initiated_by, started_at, completed_at, created_at
        ) VALUES (
            ?, ?::bulk_action_type, ?::jsonb, ?, ?::bulk_job_status, ?,
            ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?
        )
        """,
        row.id(),
        row.action(),
        writeJson(row.payload()),
        row.pharmacyIds().toArray(UUID[]::new),
        row.status(),
        row.totalPharmacies(),
        row.processed(),
        row.succeeded(),
        row.failed(),
        row.skipped(),
        writeJson(row.skippedPharmacies()),
        writeJson(row.resultPayload()),
        row.initiatedBy(),
        row.startedAt() == null ? null : Timestamp.from(row.startedAt()),
        row.completedAt() == null ? null : Timestamp.from(row.completedAt()),
        Timestamp.from(row.createdAt()));
  }

  @Override
  public Optional<JobRow> findById(UUID jobId) {
    List<JobRow> rows =
        jdbc.query(
            """
            SELECT id, action, payload, pharmacy_ids, status, total_pharmacies,
                   processed, succeeded, failed, skipped, skipped_pharmacies, result_payload,
                   initiated_by, started_at, completed_at, created_at
            FROM bulk_action_job WHERE id = ?
            """,
            this::mapRow,
            jobId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public List<JobRow> findQueued(int limit) {
    return jdbc.query(
        """
        SELECT id, action, payload, pharmacy_ids, status, total_pharmacies,
               processed, succeeded, failed, skipped, skipped_pharmacies, result_payload,
               initiated_by, started_at, completed_at, created_at
        FROM bulk_action_job
        WHERE status = 'QUEUED'
        ORDER BY created_at ASC
        LIMIT ?
        """,
        this::mapRow,
        limit);
  }

  @Override
  public void markRunning(UUID jobId, Instant startedAt) {
    jdbc.update(
        """
        UPDATE bulk_action_job
        SET status = 'RUNNING'::bulk_job_status, started_at = ?
        WHERE id = ? AND status = 'QUEUED'::bulk_job_status
        """,
        Timestamp.from(startedAt),
        jobId);
  }

  @Override
  public void updateProgress(
      UUID jobId,
      int processed,
      int succeeded,
      int failed,
      int skipped,
      List<Map<String, Object>> skippedPharmacies) {
    jdbc.update(
        """
        UPDATE bulk_action_job
        SET processed = ?, succeeded = ?, failed = ?, skipped = ?, skipped_pharmacies = ?::jsonb
        WHERE id = ?
        """,
        processed,
        succeeded,
        failed,
        skipped,
        writeJson(skippedPharmacies),
        jobId);
  }

  @Override
  public void markCompleted(
      UUID jobId,
      int processed,
      int succeeded,
      int failed,
      int skipped,
      List<Map<String, Object>> skippedPharmacies,
      Map<String, Object> resultPayload,
      Instant completedAt) {
    jdbc.update(
        """
        UPDATE bulk_action_job
        SET status = 'COMPLETED'::bulk_job_status,
            processed = ?, succeeded = ?, failed = ?, skipped = ?,
            skipped_pharmacies = ?::jsonb, result_payload = ?::jsonb, completed_at = ?
        WHERE id = ?
        """,
        processed,
        succeeded,
        failed,
        skipped,
        writeJson(skippedPharmacies),
        writeJson(resultPayload),
        Timestamp.from(completedAt),
        jobId);
  }

  private JobRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new JobRow(
        (UUID) rs.getObject("id"),
        rs.getString("action"),
        readMap(rs.getString("payload")),
        readUuidArray(rs, "pharmacy_ids"),
        rs.getString("status"),
        rs.getInt("total_pharmacies"),
        rs.getInt("processed"),
        rs.getInt("succeeded"),
        rs.getInt("failed"),
        rs.getInt("skipped"),
        readSkipped(rs.getString("skipped_pharmacies")),
        readMap(rs.getString("result_payload")),
        (UUID) rs.getObject("initiated_by"),
        ts(rs, "started_at"),
        ts(rs, "completed_at"),
        ts(rs, "created_at"));
  }

  private String writeJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private List<Map<String, Object>> readSkipped(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, SKIPPED_TYPE);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static List<UUID> readUuidArray(ResultSet rs, String col) throws SQLException {
    Array array = rs.getArray(col);
    if (array == null) {
      return List.of();
    }
    Object[] values = (Object[]) array.getArray();
    if (values == null) {
      return List.of();
    }
    List<UUID> ids = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof UUID uuid) {
        ids.add(uuid);
      } else if (value != null) {
        ids.add(UUID.fromString(value.toString()));
      }
    }
    return ids;
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
