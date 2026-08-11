package com.nammamedmate.analytics.adapter.out.persistence;

import com.nammamedmate.analytics.application.port.out.AdminReportStore;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminReportStore implements AdminReportStore {

  private final JdbcTemplate jdbc;

  public JdbcAdminReportStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ReportDefinition> findDefinition(String reportId) {
    List<ReportDefinition> rows =
        jdbc.query(
            """
            SELECT report_id, name, category, description, default_cadence, default_format,
                   retention_years, is_active
            FROM admin_report_definitions WHERE report_id = ?
            """,
            (rs, i) -> mapDefinition(rs),
            reportId);
    return rows.stream().findFirst();
  }

  @Override
  public List<ReportDefinition> listDefinitions(String categoryOrNull) {
    if (categoryOrNull == null || categoryOrNull.isBlank()) {
      return jdbc.query(
          """
          SELECT report_id, name, category, description, default_cadence, default_format,
                 retention_years, is_active
          FROM admin_report_definitions WHERE is_active = TRUE
          ORDER BY category, report_id
          """,
          (rs, i) -> mapDefinition(rs));
    }
    return jdbc.query(
        """
        SELECT report_id, name, category, description, default_cadence, default_format,
               retention_years, is_active
        FROM admin_report_definitions
        WHERE is_active = TRUE AND category = ?
        ORDER BY report_id
        """,
        (rs, i) -> mapDefinition(rs),
        categoryOrNull.toUpperCase());
  }

  @Override
  public Optional<ScheduleRow> findSchedule(String reportId) {
    List<ScheduleRow> rows =
        jdbc.query(
            """
            SELECT id, report_id, is_enabled, cadence, format, email_recipients,
                   next_run_at, updated_by, updated_at
            FROM admin_report_schedules WHERE report_id = ?
            """,
            (rs, i) -> mapSchedule(rs),
            reportId);
    return rows.stream().findFirst();
  }

  @Override
  public void upsertSchedule(ScheduleRow row) {
    jdbc.update(
        """
        INSERT INTO admin_report_schedules (
          id, report_id, is_enabled, cadence, format, email_recipients, next_run_at, updated_by, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?)
        ON CONFLICT (report_id) DO UPDATE SET
          is_enabled = EXCLUDED.is_enabled,
          cadence = EXCLUDED.cadence,
          format = EXCLUDED.format,
          email_recipients = EXCLUDED.email_recipients,
          next_run_at = EXCLUDED.next_run_at,
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        row.id(),
        row.reportId(),
        row.enabled(),
        row.cadence(),
        row.format(),
        toTextArrayLiteral(row.emailRecipients()),
        row.nextRunAt() == null ? null : Timestamp.from(row.nextRunAt()),
        row.updatedBy(),
        Timestamp.from(row.updatedAt()));
  }

  @Override
  public Instant lastCompletedAt(String reportId) {
    return jdbc.query(
        """
        SELECT completed_at FROM admin_report_jobs
        WHERE report_id = ? AND status = 'COMPLETED' AND completed_at IS NOT NULL
        ORDER BY completed_at DESC LIMIT 1
        """,
        rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null,
        reportId);
  }

  @Override
  public void insertJob(JobRow job) {
    jdbc.update(
        """
        INSERT INTO admin_report_jobs (
          id, report_id, triggered_by, trigger_type, period_from, period_to, filters, format,
          status, progress_pct, row_count, file_size_kb, s3_key, download_url, expires_at,
          queued_at, started_at, completed_at, error_message
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        job.id(),
        job.reportId(),
        job.triggeredBy(),
        job.triggerType(),
        Date.valueOf(job.periodFrom()),
        Date.valueOf(job.periodTo()),
        job.filtersJson() == null ? "{}" : job.filtersJson(),
        job.format(),
        job.status(),
        job.progressPct(),
        job.rowCount(),
        job.fileSizeKb(),
        job.s3Key(),
        job.downloadUrl(),
        job.expiresAt() == null ? null : Timestamp.from(job.expiresAt()),
        Timestamp.from(job.queuedAt()),
        job.startedAt() == null ? null : Timestamp.from(job.startedAt()),
        job.completedAt() == null ? null : Timestamp.from(job.completedAt()),
        job.errorMessage());
  }

  @Override
  public Optional<JobRow> findJob(UUID jobId) {
    List<JobRow> rows =
        jdbc.query(
            """
            SELECT id, report_id, triggered_by, trigger_type, period_from, period_to, filters::text,
                   format, status, progress_pct, row_count, file_size_kb, s3_key, download_url,
                   expires_at, queued_at, started_at, completed_at, error_message
            FROM admin_report_jobs WHERE id = ?
            """,
            (rs, i) -> mapJob(rs),
            jobId);
    return rows.stream().findFirst();
  }

  @Override
  public int countActiveJobs(UUID triggeredBy) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM admin_report_jobs
            WHERE triggered_by = ? AND status IN ('QUEUED', 'RUNNING')
            """,
            Integer.class,
            triggeredBy);
    return nzInt(n);
  }

  @Override
  public List<UUID> findQueuedJobIds(int limit) {
    return jdbc.query(
        """
        SELECT id FROM admin_report_jobs
        WHERE status = 'QUEUED'
        ORDER BY queued_at ASC
        LIMIT ?
        """,
        (rs, i) -> (UUID) rs.getObject(1),
        limit);
  }

  @Override
  public List<UUID> findTimedOutJobIds(Instant olderThan) {
    return jdbc.query(
        """
        SELECT id FROM admin_report_jobs
        WHERE status IN ('QUEUED', 'RUNNING') AND queued_at < ?
        """,
        (rs, i) -> (UUID) rs.getObject(1),
        Timestamp.from(olderThan));
  }

  @Override
  public void markJobRunning(UUID jobId, Instant startedAt) {
    jdbc.update(
        """
        UPDATE admin_report_jobs
        SET status = 'RUNNING', progress_pct = 10, started_at = ?
        WHERE id = ? AND status = 'QUEUED'
        """,
        Timestamp.from(startedAt),
        jobId);
  }

  @Override
  public void markJobCompleted(
      UUID jobId,
      int progressPct,
      int rowCount,
      int fileSizeKb,
      String s3Key,
      String downloadUrl,
      Instant expiresAt,
      Instant completedAt) {
    jdbc.update(
        """
        UPDATE admin_report_jobs
        SET status = 'COMPLETED', progress_pct = ?, row_count = ?, file_size_kb = ?,
            s3_key = ?, download_url = ?, expires_at = ?, completed_at = ?, error_message = NULL
        WHERE id = ?
        """,
        progressPct,
        rowCount,
        fileSizeKb,
        s3Key,
        downloadUrl,
        Timestamp.from(expiresAt),
        Timestamp.from(completedAt),
        jobId);
  }

  @Override
  public void markJobFailed(UUID jobId, String errorMessage, Instant completedAt) {
    jdbc.update(
        """
        UPDATE admin_report_jobs
        SET status = 'FAILED', progress_pct = 100, completed_at = ?, error_message = ?
        WHERE id = ?
        """,
        Timestamp.from(completedAt),
        errorMessage,
        jobId);
  }

  @Override
  public void refreshDownloadUrl(UUID jobId, String downloadUrl, Instant expiresAt) {
    jdbc.update(
        """
        UPDATE admin_report_jobs SET download_url = ?, expires_at = ? WHERE id = ?
        """,
        downloadUrl,
        Timestamp.from(expiresAt),
        jobId);
  }

  @Override
  public List<HistoryRow> listHistory(String categoryOrNull, Instant now, int limit, int offset) {
    Instant twoYearsAgo = now.minusSeconds(2L * 365 * 24 * 3600);
    Instant fiveYearsAgo = now.minusSeconds(5L * 365 * 24 * 3600);
    String cat = normalizeCategory(categoryOrNull);
    return jdbc.query(
        """
        SELECT j.id, j.report_id, j.triggered_by, j.trigger_type, j.period_from, j.period_to,
               j.filters::text, j.format, j.status, j.progress_pct, j.row_count, j.file_size_kb,
               j.s3_key, j.download_url, j.expires_at, j.queued_at, j.started_at, j.completed_at,
               j.error_message, d.name, d.category,
               CASE
                 WHEN j.trigger_type = 'SCHEDULED' THEN 'SCHEDULER'
                 ELSE COALESCE(a.email, j.triggered_by::text, 'unknown')
               END AS generated_by_label
        FROM admin_report_jobs j
        JOIN admin_report_definitions d ON d.report_id = j.report_id
        LEFT JOIN admin_staff a ON a.id = j.triggered_by
        WHERE j.status IN ('COMPLETED', 'FAILED')
          AND (? IS NULL OR d.category = ?)
          AND (
            (d.category = 'COMPLIANCE' AND j.completed_at >= ?)
            OR (d.category <> 'COMPLIANCE' AND j.completed_at >= ?)
          )
        ORDER BY j.completed_at DESC NULLS LAST
        LIMIT ? OFFSET ?
        """,
        (rs, i) ->
            new HistoryRow(
                mapJob(rs),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("generated_by_label")),
        cat,
        cat,
        Timestamp.from(fiveYearsAgo),
        Timestamp.from(twoYearsAgo),
        limit,
        offset);
  }

  @Override
  public long countHistory(String categoryOrNull, Instant now) {
    Instant twoYearsAgo = now.minusSeconds(2L * 365 * 24 * 3600);
    Instant fiveYearsAgo = now.minusSeconds(5L * 365 * 24 * 3600);
    String cat = normalizeCategory(categoryOrNull);
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM admin_report_jobs j
            JOIN admin_report_definitions d ON d.report_id = j.report_id
            WHERE j.status IN ('COMPLETED', 'FAILED')
              AND (? IS NULL OR d.category = ?)
              AND (
                (d.category = 'COMPLIANCE' AND j.completed_at >= ?)
                OR (d.category <> 'COMPLIANCE' AND j.completed_at >= ?)
              )
            """,
            Long.class,
            cat,
            cat,
            Timestamp.from(fiveYearsAgo),
            Timestamp.from(twoYearsAgo));
    return nz(n);
  }

  static String normalizeCategory(String categoryOrNull) {
    if (categoryOrNull == null) {
      return null;
    }
    if (categoryOrNull.isBlank()) {
      return null;
    }
    return categoryOrNull.toUpperCase();
  }

  @Override
  public long estimateRows(
      String reportId, LocalDate from, LocalDate to, Map<String, Object> filters) {
    Date f = Date.valueOf(from);
    return switch (reportId) {
      case "TAX_GSTR8_PREP" ->
          count(
              """
              SELECT COUNT(*) FROM tcs_register
              WHERE deleted_at IS NULL
                AND month >= ? AND month <= ?
              """,
              from.toString().substring(0, 7),
              to.toString().substring(0, 7));
      case "COMPLIANCE_SCHEDULE_H" ->
          count(
              """
              SELECT COUNT(*) FROM schedule_drug_register_entry
              WHERE schedule IN ('H1') AND dispensed_at >= ? AND dispensed_at < ?
              """,
              Timestamp.from(from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
              Timestamp.from(to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
      case "SCHEDULE_X_REGISTER" ->
          count(
              """
              SELECT COUNT(*) FROM schedule_drug_register_entry
              WHERE schedule = 'X' AND dispensed_at >= ? AND dispensed_at < ?
              """,
              Timestamp.from(from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
              Timestamp.from(to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
      case "COHORT_RETENTION" -> count("SELECT COUNT(*) FROM analytics_cohort_retention");
      case "ACQUISITION_MIX" ->
          count(
              """
              SELECT COUNT(*) FROM analytics_acquisition_daily
              WHERE snapshot_date >= ? AND snapshot_date <= ?
              """,
              f,
              Date.valueOf(to));
      case "SLA_BREACHES", "ORDER_FULFILMENT", "CANCELLATION_ANALYSIS", "RIDER_PERFORMANCE" ->
          count(
              """
              SELECT COUNT(*) FROM analytics_ops_snapshots
              WHERE zone_id IS NULL AND snapshot_date >= ? AND snapshot_date <= ?
              """,
              f,
              Date.valueOf(to));
      case "GMV_COMMISSION_PAYOUTS", "PLATFORM_PNL", "REFUND_SUMMARY", "SETTLEMENT_SUMMARY" ->
          count(
              """
              SELECT COUNT(*) FROM analytics_daily_snapshots
              WHERE zone_id IS NULL AND snapshot_date >= ? AND snapshot_date <= ?
              """,
              f,
              Date.valueOf(to));
      case "DRUG_RECALL_IMPACT" ->
          count(
              """
              SELECT COUNT(*) FROM orders
              WHERE deleted_at IS NULL AND created_at >= ? AND created_at < ?
              """,
              Timestamp.from(from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
              Timestamp.from(to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
      default -> 0L;
    };
  }

  @Override
  public ReportRows generateRows(
      String reportId, LocalDate from, LocalDate to, Map<String, Object> filters) {
    return switch (reportId) {
      case "TAX_GSTR8_PREP" -> generateGstr8(from, to);
      case "COMPLIANCE_SCHEDULE_H" -> generateScheduleRegister(from, to, "H1");
      case "SCHEDULE_X_REGISTER" -> generateScheduleRegister(from, to, "X");
      case "COHORT_RETENTION" -> generateCohort();
      case "ACQUISITION_MIX" -> generateAcquisition(from, to);
      case "GMV_COMMISSION_PAYOUTS" -> generatePlatformDaily(from, to, "gmv_commission");
      case "PLATFORM_PNL" -> generatePlatformDaily(from, to, "pnl");
      case "REFUND_SUMMARY" -> generatePlatformDaily(from, to, "refund");
      case "SETTLEMENT_SUMMARY" -> generatePlatformDaily(from, to, "settlement");
      case "ORDER_FULFILMENT", "SLA_BREACHES", "CANCELLATION_ANALYSIS", "RIDER_PERFORMANCE" ->
          generateOpsDaily(from, to, reportId);
      case "DRUG_RECALL_IMPACT" -> generateRecallStub(from, to);
      default -> new ReportRows(List.of("report_id"), List.of(List.of(reportId)), 0L);
    };
  }

  @Override
  public long ledgerTcsTotalPaise(LocalDate from, LocalDate to) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(credit_paise), 0)
            FROM financial_ledger
            WHERE entry_type IN ('TCS', 'TCS_COLLECTED')
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
            Timestamp.from(to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
    return nz(n);
  }

  @Override
  public List<ScheduleRow> findDueSchedules(Instant now) {
    return jdbc.query(
        """
        SELECT id, report_id, is_enabled, cadence, format, email_recipients,
               next_run_at, updated_by, updated_at
        FROM admin_report_schedules
        WHERE is_enabled = TRUE AND next_run_at IS NOT NULL AND next_run_at <= ?
        """,
        (rs, i) -> mapSchedule(rs),
        Timestamp.from(now));
  }

  private ReportRows generateGstr8(LocalDate from, LocalDate to) {
    String monthFrom = from.toString().substring(0, 7);
    String monthTo = to.toString().substring(0, 7);
    List<List<String>> rows = new ArrayList<>();
    Long totalBox =
        jdbc.query(
            """
            SELECT pharmacy_id, pharmacy_name, gstin, pan, gmv_paise, tcs_collected_paise,
                   cgst_tcs_paise, sgst_tcs_paise, month
            FROM tcs_register
            WHERE deleted_at IS NULL AND month >= ? AND month <= ?
            ORDER BY month, pharmacy_id
            """,
            rs -> {
              long sum = 0L;
              while (rs.next()) {
                long tcs = rs.getLong("tcs_collected_paise");
                sum += tcs;
                rows.add(
                    List.of(
                        rs.getString("month"),
                        String.valueOf(rs.getObject("pharmacy_id")),
                        nullToEmpty(rs.getString("pharmacy_name")),
                        nullToEmpty(rs.getString("gstin")),
                        nullToEmpty(rs.getString("pan")),
                        String.valueOf(rs.getLong("gmv_paise")),
                        String.valueOf(tcs),
                        String.valueOf(rs.getLong("cgst_tcs_paise")),
                        String.valueOf(rs.getLong("sgst_tcs_paise"))));
              }
              return sum;
            },
            monthFrom,
            monthTo);
    long total = nz(totalBox);
    return new ReportRows(
        List.of(
            "month",
            "pharmacy_id",
            "pharmacy_name",
            "gstin",
            "pan",
            "gmv_paise",
            "tcs_collected_paise",
            "cgst_tcs_paise",
            "sgst_tcs_paise"),
        rows,
        total);
  }

  private ReportRows generateScheduleRegister(LocalDate from, LocalDate to, String schedule) {
    // pan_ref/aadhaar_ref reserved for compliance-authorized exports (PII); empty until KYC join.
    List<List<String>> rows =
        jdbc.query(
            """
            SELECT e.rx_reference_no, e.patient_name, e.drug_name, e.quantity_issued,
                   e.pharmacy_id, e.dispensed_at
            FROM schedule_drug_register_entry e
            WHERE e.schedule = ? AND e.dispensed_at >= ? AND e.dispensed_at < ?
            ORDER BY e.dispensed_at
            """,
            (rs, i) ->
                List.of(
                    nullToEmpty(rs.getString("rx_reference_no")),
                    nullToEmpty(rs.getString("patient_name")),
                    nullToEmpty(rs.getString("drug_name")),
                    String.valueOf(rs.getInt("quantity_issued")),
                    String.valueOf(rs.getObject("pharmacy_id")),
                    rs.getTimestamp("dispensed_at").toInstant().toString(),
                    "",
                    ""),
            schedule,
            Timestamp.from(from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
            Timestamp.from(to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
    return new ReportRows(
        List.of(
            "rx_reference_no",
            "patient_name",
            "drug_name",
            "quantity_issued",
            "pharmacy_id",
            "dispensed_at",
            "pan_ref",
            "aadhaar_ref"),
        rows,
        0L);
  }

  private ReportRows generateCohort() {
    List<List<String>> rows =
        jdbc.query(
            """
            SELECT cohort_week, elapsed_week, retained_count, cohort_size
            FROM analytics_cohort_retention
            ORDER BY cohort_week DESC, elapsed_week
            LIMIT 200
            """,
            (rs, i) ->
                List.of(
                    nullToEmpty(rs.getString("cohort_week")),
                    String.valueOf(rs.getInt("elapsed_week")),
                    String.valueOf(rs.getInt("retained_count")),
                    String.valueOf(rs.getInt("cohort_size"))));
    return new ReportRows(
        List.of("cohort_week", "elapsed_week", "retained_count", "cohort_size"), rows, 0L);
  }

  private ReportRows generateAcquisition(LocalDate from, LocalDate to) {
    List<List<String>> rows =
        jdbc.query(
            """
            SELECT snapshot_date, source, new_users
            FROM analytics_acquisition_daily
            WHERE snapshot_date >= ? AND snapshot_date <= ?
            ORDER BY snapshot_date, source
            """,
            (rs, i) ->
                List.of(
                    rs.getDate("snapshot_date").toLocalDate().toString(),
                    nullToEmpty(rs.getString("source")),
                    String.valueOf(rs.getInt("new_users"))),
            Date.valueOf(from),
            Date.valueOf(to));
    return new ReportRows(List.of("snapshot_date", "source", "new_users"), rows, 0L);
  }

  private ReportRows generatePlatformDaily(LocalDate from, LocalDate to, String kind) {
    List<List<String>> rows =
        jdbc.query(
            """
            SELECT snapshot_date, gmv_paise, orders_count, commission_paise, refunds_paise
            FROM analytics_daily_snapshots
            WHERE zone_id IS NULL AND snapshot_date >= ? AND snapshot_date <= ?
            ORDER BY snapshot_date
            """,
            (rs, i) ->
                List.of(
                    rs.getDate("snapshot_date").toLocalDate().toString(),
                    String.valueOf(rs.getLong("gmv_paise")),
                    String.valueOf(rs.getInt("orders_count")),
                    String.valueOf(rs.getLong("commission_paise")),
                    String.valueOf(rs.getLong("refunds_paise")),
                    kind),
            Date.valueOf(from),
            Date.valueOf(to));
    return new ReportRows(
        List.of(
            "snapshot_date",
            "gmv_paise",
            "orders_count",
            "commission_paise",
            "refunds_paise",
            "kind"),
        rows,
        0L);
  }

  private ReportRows generateOpsDaily(LocalDate from, LocalDate to, String reportId) {
    List<List<String>> rows =
        jdbc.query(
            """
            SELECT snapshot_date, orders_placed, sla_breached_count, orders_cancelled
            FROM analytics_ops_snapshots
            WHERE zone_id IS NULL AND snapshot_date >= ? AND snapshot_date <= ?
            ORDER BY snapshot_date
            """,
            (rs, i) ->
                List.of(
                    rs.getDate("snapshot_date").toLocalDate().toString(),
                    String.valueOf(rs.getInt("orders_placed")),
                    String.valueOf(rs.getInt("sla_breached_count")),
                    String.valueOf(rs.getInt("orders_cancelled")),
                    reportId),
            Date.valueOf(from),
            Date.valueOf(to));
    return new ReportRows(
        List.of(
            "snapshot_date",
            "orders_placed",
            "sla_breached_count",
            "orders_cancelled",
            "report_id"),
        rows,
        0L);
  }

  private ReportRows generateRecallStub(LocalDate from, LocalDate to) {
    return new ReportRows(
        List.of("period_from", "period_to", "note"),
        List.of(List.of(from.toString(), to.toString(), "recall_impact_snapshot")),
        0L);
  }

  private long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return nz(n);
  }

  static long nz(Long n) {
    return n == null ? 0L : n;
  }

  static int nzInt(Integer n) {
    return n == null ? 0 : n;
  }

  private static ReportDefinition mapDefinition(ResultSet rs) throws SQLException {
    return new ReportDefinition(
        rs.getString("report_id"),
        rs.getString("name"),
        rs.getString("category"),
        rs.getString("description"),
        rs.getString("default_cadence"),
        rs.getString("default_format"),
        rs.getInt("retention_years"),
        rs.getBoolean("is_active"));
  }

  private ScheduleRow mapSchedule(ResultSet rs) throws SQLException {
    Array arr = rs.getArray("email_recipients");
    List<String> emails = emailsFromArray(arr);
    return new ScheduleRow(
        (UUID) rs.getObject("id"),
        rs.getString("report_id"),
        rs.getBoolean("is_enabled"),
        rs.getString("cadence"),
        rs.getString("format"),
        emails,
        instantOrNull(rs.getTimestamp("next_run_at")),
        (UUID) rs.getObject("updated_by"),
        instantOrEpoch(rs.getTimestamp("updated_at")));
  }

  private static JobRow mapJob(ResultSet rs) throws SQLException {
    return new JobRow(
        (UUID) rs.getObject("id"),
        rs.getString("report_id"),
        (UUID) rs.getObject("triggered_by"),
        rs.getString("trigger_type"),
        rs.getDate("period_from").toLocalDate(),
        rs.getDate("period_to").toLocalDate(),
        rs.getString("filters"),
        rs.getString("format"),
        rs.getString("status"),
        rs.getInt("progress_pct"),
        (Integer) rs.getObject("row_count"),
        (Integer) rs.getObject("file_size_kb"),
        rs.getString("s3_key"),
        rs.getString("download_url"),
        instantOrNull(rs.getTimestamp("expires_at")),
        rs.getTimestamp("queued_at").toInstant(),
        instantOrNull(rs.getTimestamp("started_at")),
        instantOrNull(rs.getTimestamp("completed_at")),
        rs.getString("error_message"));
  }

  static List<String> emailsFromArray(Array arr) throws SQLException {
    if (arr == null) {
      return List.of();
    }
    Object raw = arr.getArray();
    if (raw instanceof String[] s) {
      return Arrays.asList(s);
    }
    if (raw instanceof Object[] o) {
      return Arrays.stream(o).map(String::valueOf).toList();
    }
    return List.of();
  }

  static Instant instantOrNull(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  static Instant instantOrEpoch(Timestamp ts) {
    return ts == null ? Instant.EPOCH : ts.toInstant();
  }

  static String toTextArrayLiteral(List<String> values) {
    if (values == null) {
      return "{}";
    }
    if (values.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      String v =
          values.get(i) == null ? "" : values.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
      sb.append('"').append(v).append('"');
    }
    sb.append('}');
    return sb.toString();
  }

  static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
