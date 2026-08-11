package com.nammamedmate.analytics.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.analytics.application.port.out.ReportAuditPort;
import com.nammamedmate.kernel.id.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Appends report generation events to platform audit_log (AC-009). */
@Component
public class JdbcReportAuditAdapter implements ReportAuditPort {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcReportAuditAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void recordGeneration(
      UUID actorId,
      String actorName,
      String actorRole,
      String reportId,
      UUID jobId,
      String periodFrom,
      String periodTo,
      int rowCount,
      String downloadUrl,
      Instant generatedAt) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("report_id", reportId);
    after.put("period_from", periodFrom);
    after.put("period_to", periodTo);
    after.put("row_count", rowCount);
    after.put("download_url", downloadUrl);
    after.put("generated_by", actorName);
    Map<String, Object> meta = Map.of("report_id", reportId, "row_count", rowCount);
    String actorType = actorId == null ? "SYSTEM" : "ADMIN";
    String role = actorRole == null ? "SYSTEM" : actorRole;
    String name = actorName == null || actorName.isBlank() ? "SCHEDULER" : actorName;
    jdbc.update(
        """
        INSERT INTO audit_log (
          id, entity_type, entity_id, action, actor_id, actor_role, payload, ip_address, created_at,
          actor_name, actor_type, resource_type, resource_id, before_state, after_state, metadata,
          user_agent, "timestamp"
        ) VALUES (
          ?, 'admin_report_job', ?, 'report.generate', ?, ?, ?::jsonb, CAST('0.0.0.0' AS inet), ?,
          ?, ?, 'admin_report_job', ?, NULL, ?::jsonb, ?::jsonb, NULL, ?
        )
        """,
        Ids.newId(),
        jobId,
        actorId,
        role,
        toJson(Map.of("after", after)),
        Timestamp.from(generatedAt),
        name,
        actorType,
        jobId,
        toJson(after),
        toJson(meta),
        Timestamp.from(generatedAt));
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }
}
