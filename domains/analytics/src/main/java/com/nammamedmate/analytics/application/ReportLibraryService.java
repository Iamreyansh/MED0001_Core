package com.nammamedmate.analytics.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.analytics.application.port.out.AdminReportStore;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.HistoryRow;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.JobRow;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ReportDefinition;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ReportRows;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ScheduleRow;
import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.ReportAuditPort;
import com.nammamedmate.analytics.application.port.out.ReportDeliveryEmailPort;
import com.nammamedmate.analytics.domain.ReportScheduleTimes;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** EPIC-016 STORY-006 admin report library. */
@Service
public class ReportLibraryService {

  public static final int ASYNC_ROW_THRESHOLD = 10_000;
  public static final int MAX_ACTIVE_JOBS = 5;
  public static final Duration DOWNLOAD_TTL = Duration.ofDays(7);
  public static final Duration JOB_TIMEOUT = Duration.ofHours(24);

  public record GenerateResult(boolean asyncAccepted, Map<String, Object> data) {
    public GenerateResult {
      data = Map.copyOf(data);
    }
  }

  public record HistoryResult(Map<String, Object> data, PaginationMeta meta) {
    public HistoryResult {
      data = Map.copyOf(data);
    }
  }

  private final AdminReportStore store;
  private final AnalyticsExportPort exports;
  private final ReportAuditPort audit;
  private final ReportDeliveryEmailPort email;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ReportLibraryService(
      AdminReportStore store,
      AnalyticsExportPort exports,
      ReportAuditPort audit,
      ReportDeliveryEmailPort email,
      ObjectMapper objectMapper,
      Clock clock) {
    this.store = store;
    this.exports = exports;
    this.audit = audit;
    this.email = email;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public Map<String, Object> listReports(MedmatePrincipal principal, String category) {
    requireGenerateAccess(principal);
    if (category != null && !category.isBlank()) {
      String cat = category.toUpperCase(Locale.ROOT);
      requireCategoryAccess(principal, cat);
      return wrapReports(store.listDefinitions(cat));
    }
    List<ReportDefinition> all = store.listDefinitions(null);
    List<ReportDefinition> visible =
        all.stream().filter(d -> canAccessCategory(principal, d.category())).toList();
    return wrapReports(visible);
  }

  public GenerateResult generate(
      MedmatePrincipal principal,
      String reportId,
      LocalDate periodFrom,
      LocalDate periodTo,
      Map<String, Object> filters,
      String format,
      Boolean async) {
    requireGenerateAccess(principal);
    ReportDefinition def =
        store
            .findDefinition(reportId)
            .orElseThrow(() -> new AppException("REPORT_NOT_FOUND", "Report not found", 404));
    requireCategoryAccess(principal, def.category());
    if (periodFrom == null || periodTo == null || periodFrom.isAfter(periodTo)) {
      throw new AppException("INVALID_PERIOD_RANGE", "period_from must be <= period_to", 422);
    }
    if (store.countActiveJobs(principal.subject()) >= MAX_ACTIVE_JOBS) {
      throw new AppException("TOO_MANY_JOBS", "More than 5 active jobs for this user", 429);
    }
    Map<String, Object> filterMap = filters == null ? Map.of() : filters;
    String fmt = resolveFormat(format, def.defaultFormat());
    long estimated = store.estimateRows(reportId, periodFrom, periodTo, filterMap);
    boolean forceAsync = estimated > ASYNC_ROW_THRESHOLD;
    boolean wantAsync = forceAsync || async == null || async;
    Instant now = clock.instant();
    UUID jobId = Ids.newId();

    if (!wantAsync) {
      return completeSync(principal, def, jobId, periodFrom, periodTo, filterMap, fmt, now);
    }

    store.insertJob(
        new JobRow(
            jobId,
            reportId,
            principal.subject(),
            "MANUAL",
            periodFrom,
            periodTo,
            toJson(filterMap),
            fmt,
            "QUEUED",
            0,
            null,
            null,
            null,
            null,
            null,
            now,
            null,
            null,
            null));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", jobId.toString());
    data.put("report_id", reportId);
    data.put("status", "QUEUED");
    data.put("estimated_rows", estimated);
    data.put("queued_at", now.toString());
    return new GenerateResult(true, data);
  }

  public Map<String, Object> jobStatus(MedmatePrincipal principal, UUID jobId) {
    requireAnyAdmin(principal);
    expireTimedOutJobs();
    JobRow job =
        store
            .findJob(jobId)
            .orElseThrow(() -> new AppException("JOB_NOT_FOUND", "Job not found", 404));
    ReportDefinition def =
        store
            .findDefinition(job.reportId())
            .orElseThrow(() -> new AppException("REPORT_NOT_FOUND", "Report not found", 404));
    // Category RBAC applies to scheduled jobs (triggeredBy null) as well as manual ones.
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_SUPPORT) {
      requireCategoryAccess(principal, def.category());
      if (job.triggeredBy() != null && !job.triggeredBy().equals(principal.subject())) {
        throw new AppException("FORBIDDEN", "Job belongs to a different user", 403);
      }
    }
    boolean allowDownload = principal.role() != AuthRole.ADMIN_SUPPORT;
    if (allowDownload && "COMPLETED".equals(job.status()) && job.s3Key() != null) {
      AnalyticsExportPort.SignedUrl signed = exports.signedGet(job.s3Key(), DOWNLOAD_TTL);
      store.refreshDownloadUrl(jobId, signed.url(), signed.expiresAt());
      Map<String, Object> data = jobMap(job);
      data.put("download_url", signed.url());
      data.put("expires_at", signed.expiresAt().toString());
      return data;
    }
    Map<String, Object> data = jobMap(job);
    if (!allowDownload) {
      data.put("download_url", null);
    }
    return data;
  }

  public Map<String, Object> updateSchedule(
      MedmatePrincipal principal,
      String reportId,
      Boolean enabled,
      String cadence,
      List<String> emailRecipients,
      String format) {
    requireGenerateAccess(principal);
    ReportDefinition def =
        store
            .findDefinition(reportId)
            .orElseThrow(() -> new AppException("REPORT_NOT_FOUND", "Report not found", 404));
    requireCategoryAccess(principal, def.category());
    if ("ON_DEMAND".equals(def.defaultCadence())) {
      String requested = cadence == null ? "ON_DEMAND" : cadence;
      if ("ON_DEMAND".equalsIgnoreCase(requested)) {
        throw new AppException("INVALID_CADENCE", "ON_DEMAND reports cannot be scheduled", 400);
      }
    }
    boolean on = enabled != null && enabled;
    List<String> recipients = emailRecipients == null ? List.of() : emailRecipients;
    if (on && recipients.isEmpty()) {
      throw new AppException("MISSING_RECIPIENTS", "enabled=true requires email_recipients", 422);
    }
    String cad =
        cadence == null || cadence.isBlank()
            ? ("ON_DEMAND".equals(def.defaultCadence()) ? "WEEKLY" : def.defaultCadence())
            : cadence.toUpperCase(Locale.ROOT);
    if (!Set.of("DAILY", "WEEKLY", "MONTHLY").contains(cad)) {
      throw new AppException("INVALID_CADENCE", "Invalid cadence", 400);
    }
    Instant now = clock.instant();
    Instant next = on ? ReportScheduleTimes.nextRun(cad, now, clock) : null;
    UUID scheduleId = store.findSchedule(reportId).map(ScheduleRow::id).orElse(Ids.newId());
    ScheduleRow row =
        new ScheduleRow(
            scheduleId,
            reportId,
            on,
            cad,
            resolveFormat(format, def.defaultFormat()),
            recipients,
            next,
            principal.subject(),
            now);
    store.upsertSchedule(row);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("report_id", reportId);
    data.put("is_scheduled_enabled", on);
    data.put("cadence", cad);
    data.put("next_run_at", next == null ? null : next.toString());
    data.put("email_recipients", recipients);
    data.put("updated_by", principal.subject().toString());
    data.put("updated_at", now.toString());
    return data;
  }

  public HistoryResult history(
      MedmatePrincipal principal, String category, Integer page, Integer limit) {
    requireAnyAdmin(principal);
    if (category != null && !category.isBlank()) {
      String cat = category.toUpperCase(Locale.ROOT);
      if (principal.role() != AuthRole.ADMIN_SUPPORT && !canAccessCategory(principal, cat)) {
        throw new AppException("FORBIDDEN", "Role cannot access this report category", 403);
      }
    }
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    Instant now = clock.instant();
    String cat = category == null || category.isBlank() ? null : category.toUpperCase(Locale.ROOT);
    // Support: history only; filter to accessible categories for non-super/non-support.
    List<HistoryRow> rows = store.listHistory(cat, now, lim, (p - 1) * lim);
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_SUPPORT) {
      rows = rows.stream().filter(r -> canAccessCategory(principal, r.category())).toList();
    }
    long total = store.countHistory(cat, now);
    List<Map<String, Object>> history = new ArrayList<>();
    for (HistoryRow row : rows) {
      JobRow job = row.job();
      String download = job.downloadUrl();
      Instant expires = job.expiresAt();
      boolean supportBlockedCompliance =
          principal.role() == AuthRole.ADMIN_SUPPORT && "COMPLIANCE".equals(row.category());
      if (!supportBlockedCompliance && "COMPLETED".equals(job.status()) && job.s3Key() != null) {
        AnalyticsExportPort.SignedUrl signed = exports.signedGet(job.s3Key(), DOWNLOAD_TTL);
        download = signed.url();
        expires = signed.expiresAt();
      }
      if (supportBlockedCompliance) {
        download = null;
        expires = null;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("job_id", job.id().toString());
      item.put("report_id", job.reportId());
      item.put("report_name", row.reportName());
      item.put("category", row.category());
      item.put("period_from", job.periodFrom().toString());
      item.put("period_to", job.periodTo().toString());
      item.put("generated_at", job.completedAt() == null ? null : job.completedAt().toString());
      item.put("generated_by", row.generatedByLabel());
      item.put("row_count", job.rowCount());
      item.put("size_kb", job.fileSizeKb());
      item.put("download_url", download);
      item.put("expires_at", expires == null ? null : expires.toString());
      item.put("status", job.status());
      history.add(item);
    }
    return new HistoryResult(Map.of("history", history), PaginationMeta.of(p, lim, total));
  }

  /** Process one queued job (poller / tests). */
  public void processJob(UUID jobId) {
    JobRow job = store.findJob(jobId).orElse(null);
    if (job == null || !"QUEUED".equals(job.status())) {
      return;
    }
    Instant now = clock.instant();
    store.markJobRunning(jobId, now);
    try {
      Map<String, Object> filters = parseFilters(job.filtersJson());
      ReportRows rows =
          store.generateRows(job.reportId(), job.periodFrom(), job.periodTo(), filters);
      if ("TAX_GSTR8_PREP".equals(job.reportId())) {
        long ledger = store.ledgerTcsTotalPaise(job.periodFrom(), job.periodTo());
        long reportTotal = rows.reconcileTotalPaise();
        if (reportTotal != ledger) {
          throw new AppException(
              "GSTR8_RECONCILE_FAILED",
              "TAX_GSTR8_PREP totals do not match TCS ledger (₹0 tolerance)",
              422);
        }
      }
      completeJob(job, rows, now);
    } catch (RuntimeException ex) {
      store.markJobFailed(
          jobId, ex instanceof AppException ae ? ae.code() : ex.getMessage(), clock.instant());
    }
  }

  public void expireTimedOutJobs() {
    Instant cutoff = clock.instant().minus(JOB_TIMEOUT);
    for (UUID id : store.findTimedOutJobIds(cutoff)) {
      store.markJobFailed(id, "JOB_TIMEOUT", clock.instant());
    }
  }

  public void processQueuedBatch(int limit) {
    expireTimedOutJobs();
    for (UUID id : store.findQueuedJobIds(limit)) {
      processJob(id);
    }
  }

  /** Due schedules at 06:00 IST cadence. */
  public void runDueSchedules() {
    Instant now = clock.instant();
    for (ScheduleRow schedule : store.findDueSchedules(now)) {
      ReportDefinition def = store.findDefinition(schedule.reportId()).orElse(null);
      if (def == null) {
        continue;
      }
      LocalDate to = LocalDate.ofInstant(now, ReportScheduleTimes.IST).minusDays(1);
      LocalDate from =
          switch (schedule.cadence()) {
            case "DAILY" -> to;
            case "WEEKLY" -> to.minusDays(6);
            default -> to.withDayOfMonth(1);
          };
      UUID jobId = Ids.newId();
      store.insertJob(
          new JobRow(
              jobId,
              schedule.reportId(),
              null,
              "SCHEDULED",
              from,
              to,
              "{}",
              schedule.format(),
              "QUEUED",
              0,
              null,
              null,
              null,
              null,
              null,
              now,
              null,
              null,
              null));
      processJob(jobId);
      JobRow done = store.findJob(jobId).orElse(null);
      if (done == null) {
        continue;
      }
      if ("COMPLETED".equals(done.status())) {
        email.sendScheduledReport(
            schedule.emailRecipients(),
            schedule.reportId(),
            schedule.format(),
            done.downloadUrl(),
            new byte[0]);
      }
      Instant next = ReportScheduleTimes.nextRun(schedule.cadence(), now, clock);
      store.upsertSchedule(
          new ScheduleRow(
              schedule.id(),
              schedule.reportId(),
              schedule.enabled(),
              schedule.cadence(),
              schedule.format(),
              schedule.emailRecipients(),
              next,
              schedule.updatedBy(),
              now));
    }
  }

  private GenerateResult completeSync(
      MedmatePrincipal principal,
      ReportDefinition def,
      UUID jobId,
      LocalDate from,
      LocalDate to,
      Map<String, Object> filters,
      String format,
      Instant now) {
    ReportRows rows = store.generateRows(def.reportId(), from, to, filters);
    if ("TAX_GSTR8_PREP".equals(def.reportId())) {
      long ledger = store.ledgerTcsTotalPaise(from, to);
      if (rows.reconcileTotalPaise() != ledger) {
        throw new AppException(
            "GSTR8_RECONCILE_FAILED",
            "TAX_GSTR8_PREP totals do not match TCS ledger (₹0 tolerance)",
            422);
      }
    }
    byte[] bytes = toCsv(rows);
    String key =
        StorageObjectKeys.key(
            StorageObjectKeys.REPORTS,
            def.reportId().toLowerCase(Locale.ROOT) + "-" + jobId + ".csv");
    exports.put(key, bytes, "text/csv");
    AnalyticsExportPort.SignedUrl signed = exports.signedGet(key, DOWNLOAD_TTL);
    int sizeKb = Math.max(1, bytes.length / 1024);
    store.insertJob(
        new JobRow(
            jobId,
            def.reportId(),
            principal.subject(),
            "MANUAL",
            from,
            to,
            toJson(filters),
            format,
            "COMPLETED",
            100,
            rows.size(),
            sizeKb,
            key,
            signed.url(),
            signed.expiresAt(),
            now,
            now,
            now,
            null));
    audit.recordGeneration(
        principal.subject(),
        principal.subject().toString(),
        principal.role().value(),
        def.reportId(),
        jobId,
        from.toString(),
        to.toString(),
        rows.size(),
        signed.url(),
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("report_id", def.reportId());
    data.put("status", "COMPLETED");
    data.put("download_url", signed.url());
    data.put("row_count", rows.size());
    data.put("generated_at", now.toString());
    data.put("expires_at", signed.expiresAt().toString());
    return new GenerateResult(false, data);
  }

  private void completeJob(JobRow job, ReportRows rows, Instant started) {
    Instant now = clock.instant();
    byte[] bytes = toCsv(rows);
    String key =
        StorageObjectKeys.key(
            StorageObjectKeys.REPORTS,
            job.reportId().toLowerCase(Locale.ROOT) + "-" + job.id() + ".csv");
    exports.put(key, bytes, "text/csv");
    AnalyticsExportPort.SignedUrl signed = exports.signedGet(key, DOWNLOAD_TTL);
    int sizeKb = Math.max(1, bytes.length / 1024);
    store.markJobCompleted(
        job.id(), 100, rows.size(), sizeKb, key, signed.url(), signed.expiresAt(), now);
    String actorName = job.triggeredBy() == null ? "SCHEDULER" : job.triggeredBy().toString();
    String actorRole = job.triggeredBy() == null ? "SYSTEM" : "admin";
    audit.recordGeneration(
        job.triggeredBy(),
        actorName,
        actorRole,
        job.reportId(),
        job.id(),
        job.periodFrom().toString(),
        job.periodTo().toString(),
        rows.size(),
        signed.url(),
        now);
  }

  private Map<String, Object> wrapReports(List<ReportDefinition> defs) {
    List<Map<String, Object>> reports = new ArrayList<>();
    for (ReportDefinition def : defs) {
      ScheduleRow schedule = store.findSchedule(def.reportId()).orElse(null);
      Instant last = store.lastCompletedAt(def.reportId());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("report_id", def.reportId());
      item.put("name", def.name());
      item.put("category", def.category());
      item.put("description", def.description());
      item.put("cadence", schedule == null ? def.defaultCadence() : schedule.cadence());
      item.put("format", schedule == null ? def.defaultFormat() : schedule.format());
      item.put("last_run_at", last == null ? null : last.toString());
      item.put(
          "next_run_at",
          schedule == null || schedule.nextRunAt() == null
              ? null
              : schedule.nextRunAt().toString());
      item.put("is_scheduled_enabled", schedule != null && schedule.enabled());
      reports.add(item);
    }
    return Map.of("reports", reports);
  }

  private static Map<String, Object> jobMap(JobRow job) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", job.id().toString());
    data.put("report_id", job.reportId());
    data.put("status", job.status());
    data.put("progress_pct", job.progressPct());
    data.put("queued_at", job.queuedAt().toString());
    data.put("started_at", job.startedAt() == null ? null : job.startedAt().toString());
    data.put("completed_at", job.completedAt() == null ? null : job.completedAt().toString());
    data.put("download_url", job.downloadUrl());
    data.put("error_message", job.errorMessage());
    if (job.rowCount() != null) {
      data.put("row_count", job.rowCount());
    }
    if (job.fileSizeKb() != null) {
      data.put("file_size_kb", job.fileSizeKb());
    }
    if (job.expiresAt() != null) {
      data.put("expires_at", job.expiresAt().toString());
    }
    return data;
  }

  private static byte[] toCsv(ReportRows rows) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.join(",", rows.headers())).append('\n');
    for (List<String> row : rows.rows()) {
      for (int i = 0; i < row.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(csvEscape(row.get(i)));
      }
      sb.append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String csvEscape(String raw) {
    if (raw == null) {
      return "";
    }
    if (needsCsvQuote(raw)) {
      return '"' + raw.replace("\"", "\"\"") + '"';
    }
    return raw;
  }

  static boolean needsCsvQuote(String raw) {
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == ',' || c == '"' || c == '\n') {
        return true;
      }
    }
    return false;
  }

  private static String resolveFormat(String requested, String fallback) {
    if (requested == null || requested.isBlank()) {
      return fallback;
    }
    String f = requested.toUpperCase(Locale.ROOT);
    if ("CSV".equals(f)) {
      return f;
    }
    if ("PDF".equals(f)) {
      return f;
    }
    return fallback;
  }

  private String toJson(Map<String, Object> filters) {
    try {
      return objectMapper.writeValueAsString(filters);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseFilters(String json) {
    if (json == null) {
      return Map.of();
    }
    if (json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private static void requireAnyAdmin(MedmatePrincipal principal) {
    if (principal == null || !principal.role().name().startsWith("ADMIN_")) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireGenerateAccess(MedmatePrincipal principal) {
    requireAnyAdmin(principal);
    if (principal.role() == AuthRole.ADMIN_SUPPORT) {
      throw new AppException("FORBIDDEN", "Support may only read report history", 403);
    }
  }

  private static void requireCategoryAccess(MedmatePrincipal principal, String category) {
    if (!canAccessCategory(principal, category)) {
      throw new AppException("FORBIDDEN", "Role cannot access this report category", 403);
    }
  }

  static boolean canAccessCategory(MedmatePrincipal principal, String category) {
    AuthRole role = principal.role();
    if (role == AuthRole.ADMIN_SUPER || role == AuthRole.ADMIN_SUPPORT) {
      return true;
    }
    if (role == AuthRole.ADMIN_FINANCE) {
      return "FINANCE".equals(category);
    }
    if (role == AuthRole.ADMIN_OPERATIONS) {
      return "OPERATIONS".equals(category) || "GROWTH".equals(category);
    }
    if (role == AuthRole.ADMIN_COMPLIANCE) {
      return "COMPLIANCE".equals(category);
    }
    return false;
  }
}
