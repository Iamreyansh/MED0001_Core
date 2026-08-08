package com.nammamedmate.settings.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.settings.application.port.out.AuditExportJobStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAuditExportJobStore implements AuditExportJobStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAuditExportJobStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insertQueued(UUID id, Map<String, Object> filters, Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO audit_export_job (id, status, filters, created_at)
        VALUES (?, 'QUEUED', ?::jsonb, ?)
        """,
        id,
        toJson(filters),
        Timestamp.from(createdAt));
  }

  @Override
  public void markCompleted(UUID id, String downloadUrl, Instant completedAt) {
    jdbc.update(
        """
        UPDATE audit_export_job
        SET status = 'COMPLETED', download_url = ?, completed_at = ?
        WHERE id = ?
        """,
        downloadUrl,
        Timestamp.from(completedAt),
        id);
  }

  @Override
  public Optional<ExportJobRow> findById(UUID id) {
    List<ExportJobRow> rows =
        jdbc.query(
            """
            SELECT id, status, filters, download_url, created_at
            FROM audit_export_job WHERE id = ?
            """,
            (rs, i) ->
                new ExportJobRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("status"),
                    readMap(rs.getString("filters")),
                    rs.getString("download_url"),
                    rs.getTimestamp("created_at").toInstant()),
            id);
    return rows.stream().findFirst();
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP);
    } catch (Exception ex) {
      return Map.of();
    }
  }
}
