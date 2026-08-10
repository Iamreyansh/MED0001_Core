package com.nammamedmate.prescription.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore;
import com.nammamedmate.prescription.domain.ComplianceFiling;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcComplianceFilingStore implements ComplianceFilingStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcComplianceFilingStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(ComplianceFiling f) {
    jdbc.update(
        """
        INSERT INTO compliance_filing (
          id, filing_type, period_from, period_to, due_date, status,
          generated_report_s3_key, generated_report_format, generated_at,
          filed_by, filed_at, reference_number, is_archived,
          overdue_alerted_at, overdue_escalation_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        f.id(),
        f.filingType(),
        Date.valueOf(f.periodFrom()),
        Date.valueOf(f.periodTo()),
        Date.valueOf(f.dueDate()),
        f.status(),
        f.generatedReportS3Key(),
        f.generatedReportFormat(),
        ts(f.generatedAt()),
        f.filedBy(),
        ts(f.filedAt()),
        f.referenceNumber(),
        f.archived(),
        ts(f.overdueAlertedAt()),
        ts(f.overdueEscalationAt()),
        Timestamp.from(f.createdAt()),
        Timestamp.from(f.updatedAt()));
  }

  @Override
  public Optional<ComplianceFiling> findById(UUID id) {
    List<ComplianceFiling> rows =
        jdbc.query("SELECT * FROM compliance_filing WHERE id = ?", (rs, i) -> mapFiling(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean existsTypePeriod(String filingType, LocalDate periodFrom, LocalDate periodTo) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM compliance_filing
            WHERE filing_type = ? AND period_from = ? AND period_to = ?
            """,
            Integer.class,
            filingType,
            Date.valueOf(periodFrom),
            Date.valueOf(periodTo));
    return n != null && n > 0;
  }

  @Override
  public void update(ComplianceFiling f) {
    jdbc.update(
        """
        UPDATE compliance_filing SET
          status = ?,
          generated_report_s3_key = ?,
          generated_report_format = ?,
          generated_at = ?,
          filed_by = ?,
          filed_at = ?,
          reference_number = ?,
          is_archived = ?,
          overdue_alerted_at = ?,
          overdue_escalation_at = ?,
          updated_at = ?
        WHERE id = ?
        """,
        f.status(),
        f.generatedReportS3Key(),
        f.generatedReportFormat(),
        ts(f.generatedAt()),
        f.filedBy(),
        ts(f.filedAt()),
        f.referenceNumber(),
        f.archived(),
        ts(f.overdueAlertedAt()),
        ts(f.overdueEscalationAt()),
        Timestamp.from(f.updatedAt()),
        f.id());
  }

  @Override
  public ListPage list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    if (!filter.includeArchived()) {
      where.append(" AND is_archived = FALSE ");
    }
    if (filter.filingType() != null) {
      where.append(" AND filing_type = ? ");
      args.add(filter.filingType());
    }
    if (filter.status() != null) {
      where.append(" AND status = ? ");
      args.add(filter.status());
    }
    if (filter.year() != null) {
      where.append(" AND EXTRACT(YEAR FROM period_from) = ? ");
      args.add(filter.year());
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM compliance_filing" + where, Long.class, args.toArray());
    long pending =
        countKpi("PENDING", filter.includeArchived(), filter.filingType(), filter.year());
    long overdue =
        countKpi("OVERDUE", filter.includeArchived(), filter.filingType(), filter.year());
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<ComplianceFiling> filings =
        jdbc.query(
            "SELECT * FROM compliance_filing"
                + where
                + " ORDER BY due_date DESC, created_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> mapFiling(rs),
            pageArgs.toArray());
    return new ListPage(filings, total == null ? 0L : total, pending, overdue);
  }

  private long countKpi(String status, boolean includeArchived, String filingType, Integer year) {
    StringBuilder where = new StringBuilder(" WHERE status = ? ");
    List<Object> args = new ArrayList<>();
    args.add(status);
    if (!includeArchived) {
      where.append(" AND is_archived = FALSE ");
    }
    if (filingType != null) {
      where.append(" AND filing_type = ? ");
      args.add(filingType);
    }
    if (year != null) {
      where.append(" AND EXTRACT(YEAR FROM period_from) = ? ");
      args.add(year);
    }
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM compliance_filing" + where, Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public int markOverdue(LocalDate today, Instant now) {
    return jdbc.update(
        """
        UPDATE compliance_filing
        SET status = 'OVERDUE', overdue_alerted_at = COALESCE(overdue_alerted_at, ?), updated_at = ?
        WHERE status = 'PENDING' AND due_date <= ? AND is_archived = FALSE
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        Date.valueOf(today));
  }

  @Override
  public List<ComplianceFiling> findPendingPastDue(LocalDate today) {
    return jdbc.query(
        """
        SELECT * FROM compliance_filing
        WHERE status = 'PENDING' AND due_date <= ? AND is_archived = FALSE
        """,
        (rs, i) -> mapFiling(rs),
        Date.valueOf(today));
  }

  @Override
  public List<ComplianceFiling> findOverdueForEscalation(LocalDate escalationDay) {
    return jdbc.query(
        """
        SELECT * FROM compliance_filing
        WHERE status = 'OVERDUE' AND due_date = ? AND overdue_escalation_at IS NULL
          AND is_archived = FALSE
        """,
        (rs, i) -> mapFiling(rs),
        Date.valueOf(escalationDay));
  }

  @Override
  public void setOverdueAlerted(UUID id, Instant at) {
    jdbc.update(
        "UPDATE compliance_filing SET overdue_alerted_at = ?, updated_at = ? WHERE id = ?",
        Timestamp.from(at),
        Timestamp.from(at),
        id);
  }

  @Override
  public void setOverdueEscalation(UUID id, Instant at) {
    jdbc.update(
        "UPDATE compliance_filing SET overdue_escalation_at = ?, updated_at = ? WHERE id = ?",
        Timestamp.from(at),
        Timestamp.from(at),
        id);
  }

  @Override
  public int archiveOlderThan(LocalDate cutoff, Instant now) {
    return jdbc.update(
        """
        UPDATE compliance_filing
        SET is_archived = TRUE, updated_at = ?
        WHERE is_archived = FALSE AND period_to < ?
        """,
        Timestamp.from(now),
        Date.valueOf(cutoff));
  }

  @Override
  public Optional<GenerateJob> findGeneratingJob(UUID filingId) {
    List<GenerateJob> rows =
        jdbc.query(
            """
            SELECT * FROM compliance_filing_generate_job
            WHERE filing_id = ? AND status = 'GENERATING'
            LIMIT 1
            """,
            (rs, i) -> mapJob(rs),
            filingId);
    return rows.stream().findFirst();
  }

  @Override
  public void insertGenerateJob(GenerateJob job) {
    jdbc.update(
        """
        INSERT INTO compliance_filing_generate_job (
          id, filing_id, format, status, storage_key, row_count,
          requested_by, generated_at, expires_at, error_message, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        job.id(),
        job.filingId(),
        job.format(),
        job.status(),
        job.storageKey(),
        job.rowCount(),
        job.requestedBy(),
        ts(job.generatedAt()),
        ts(job.expiresAt()),
        job.errorMessage(),
        Timestamp.from(job.createdAt()));
  }

  @Override
  public Optional<GenerateJob> findGenerateJob(UUID jobId) {
    List<GenerateJob> rows =
        jdbc.query(
            "SELECT * FROM compliance_filing_generate_job WHERE id = ?",
            (rs, i) -> mapJob(rs),
            jobId);
    return rows.stream().findFirst();
  }

  @Override
  public void updateGenerateJob(GenerateJob job) {
    jdbc.update(
        """
        UPDATE compliance_filing_generate_job SET
          status = ?, storage_key = ?, row_count = ?,
          generated_at = ?, expires_at = ?, error_message = ?
        WHERE id = ?
        """,
        job.status(),
        job.storageKey(),
        job.rowCount(),
        ts(job.generatedAt()),
        ts(job.expiresAt()),
        job.errorMessage(),
        job.id());
  }

  @Override
  public void appendActivity(
      UUID id,
      UUID rxId,
      UUID doctorId,
      UUID filingId,
      String action,
      UUID actorId,
      String actorRole,
      String payloadJson,
      String ipAddress,
      Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO compliance_activity_log (
          id, rx_id, action, actor_id, actor_role, payload,
          doctor_id, filing_id, ip_address, created_at
        ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        """,
        id,
        rxId,
        action,
        actorId,
        actorRole,
        payloadJson == null ? "{}" : payloadJson,
        doctorId,
        filingId,
        ipAddress,
        Timestamp.from(createdAt));
  }

  @Override
  public ActivityPage listActivity(ActivityFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    if (filter.action() != null) {
      where.append(" AND a.action = ? ");
      args.add(filter.action());
    }
    if (filter.actorId() != null) {
      where.append(" AND a.actor_id = ? ");
      args.add(filter.actorId());
    }
    if (filter.fromInclusive() != null) {
      where.append(" AND a.created_at >= ? ");
      args.add(Timestamp.from(filter.fromInclusive()));
    }
    if (filter.toExclusive() != null) {
      where.append(" AND a.created_at < ? ");
      args.add(Timestamp.from(filter.toExclusive()));
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM compliance_activity_log a" + where, Long.class, args.toArray());
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<Map<String, Object>> items =
        jdbc.query(
            """
            SELECT a.id, a.action, a.actor_id, a.actor_role, a.rx_id, a.payload::text, a.created_at,
                   s.name AS actor_name
            FROM compliance_activity_log a
            LEFT JOIN admin_staff s ON s.id = a.actor_id AND s.deleted_at IS NULL
            """
                + where
                + " ORDER BY a.created_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("log_id", rs.getObject("id"));
              m.put("action", rs.getString("action"));
              m.put("actor_id", rs.getObject("actor_id"));
              m.put("actor_name", rs.getString("actor_name"));
              m.put("actor_role", rs.getString("actor_role"));
              m.put("rx_id", rs.getObject("rx_id"));
              m.put("payload", parseMap(rs.getString("payload")));
              m.put("created_at", rs.getTimestamp("created_at").toInstant());
              return m;
            },
            pageArgs.toArray());
    return new ActivityPage(items, total == null ? 0L : total);
  }

  @Override
  public Optional<String> adminName(UUID adminId) {
    List<String> rows =
        jdbc.query(
            "SELECT name FROM admin_staff WHERE id = ? AND deleted_at IS NULL",
            (rs, i) -> rs.getString("name"),
            adminId);
    return rows.stream().findFirst();
  }

  private ComplianceFiling mapFiling(ResultSet rs) throws SQLException {
    return new ComplianceFiling(
        (UUID) rs.getObject("id"),
        rs.getString("filing_type"),
        rs.getDate("period_from").toLocalDate(),
        rs.getDate("period_to").toLocalDate(),
        rs.getDate("due_date").toLocalDate(),
        rs.getString("status"),
        rs.getString("generated_report_s3_key"),
        rs.getString("generated_report_format"),
        instant(rs.getTimestamp("generated_at")),
        (UUID) rs.getObject("filed_by"),
        instant(rs.getTimestamp("filed_at")),
        rs.getString("reference_number"),
        rs.getBoolean("is_archived"),
        instant(rs.getTimestamp("overdue_alerted_at")),
        instant(rs.getTimestamp("overdue_escalation_at")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private GenerateJob mapJob(ResultSet rs) throws SQLException {
    Integer rowCount = (Integer) rs.getObject("row_count");
    return new GenerateJob(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("filing_id"),
        rs.getString("format"),
        rs.getString("status"),
        rs.getString("storage_key"),
        rowCount,
        (UUID) rs.getObject("requested_by"),
        instant(rs.getTimestamp("generated_at")),
        instant(rs.getTimestamp("expires_at")),
        rs.getString("error_message"),
        rs.getTimestamp("created_at").toInstant());
  }

  private Map<String, Object> parseMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
