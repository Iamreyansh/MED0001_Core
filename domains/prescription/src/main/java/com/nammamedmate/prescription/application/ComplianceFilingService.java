package com.nammamedmate.prescription.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ActivityFilter;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ActivityPage;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.GenerateJob;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ListFilter;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ListPage;
import com.nammamedmate.prescription.application.port.out.InventoryBanPort;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore;
import com.nammamedmate.prescription.domain.ComplianceFiling;
import com.nammamedmate.prescription.domain.ScheduleDrugRegisterEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceFilingService {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Duration DOWNLOAD_TTL = Duration.ofHours(24);
  private static final Set<AuthRole> FILING_READ =
      Set.of(
          AuthRole.ADMIN_COMPLIANCE,
          AuthRole.ADMIN_SUPER,
          AuthRole.ADMIN_OPERATIONS,
          AuthRole.ADMIN_FINANCE);
  private static final Set<AuthRole> FILING_MUTATE =
      Set.of(AuthRole.ADMIN_COMPLIANCE, AuthRole.ADMIN_SUPER);
  private static final Set<AuthRole> ACTIVITY_READ =
      Set.of(AuthRole.ADMIN_COMPLIANCE, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Set<String> FILING_TYPES =
      Set.of("SCHEDULE_H1_REGISTER", "SCHEDULE_X_REGISTER", "ADVERSE_EVENTS", "DRUG_RECALL");
  private static final Set<String> STATUSES = Set.of("PENDING", "FILED", "OVERDUE");
  private static final Set<String> FORMATS = Set.of("CSV", "PDF");
  private static final Set<String> ACTIVITY_ACTIONS =
      Set.of(
          "RX_VERIFIED",
          "RX_FLAGGED",
          "DOCTOR_VERIFIED",
          "DOCTOR_BLACKLISTED",
          "REGISTER_EXPORTED",
          "FILING_MARKED",
          "FILING_GENERATED",
          "DRUG_RECALLED");

  /** ponytail: fixed national holidays (MMDD); upgrade to full Indian calendar feed. */
  private static final Set<Integer> FIXED_HOLIDAYS_MMDD = Set.of(126, 815, 1002);

  private final ComplianceFilingStore store;
  private final ScheduleDrugRegisterStore registerStore;
  private final ComplianceExportStore exportStore;
  private final InventoryBanPort inventoryBan;
  private final NotificationDispatchPort notifications;
  private final ObjectMapper objectMapper;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public ComplianceFilingService(
      ComplianceFilingStore store,
      ScheduleDrugRegisterStore registerStore,
      ComplianceExportStore exportStore,
      InventoryBanPort inventoryBan,
      NotificationDispatchPort notifications,
      ObjectMapper objectMapper,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.registerStore = registerStore;
    this.exportStore = exportStore;
    this.inventoryBan = inventoryBan;
    this.notifications = notifications;
    this.objectMapper = objectMapper;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public record ActivityResult(List<Map<String, Object>> data, PaginationMeta meta) {
    public ActivityResult {
      data = data == null ? List.of() : List.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public ListResult listFilings(
      MedmatePrincipal principal,
      String filingType,
      String status,
      Integer year,
      Boolean includeArchived,
      Integer page,
      Integer limit) {
    requireRole(principal, FILING_READ);
    rateLimit("cfile:list:" + principal.subject(), 30, 60);
    String type = normalizeOptional(filingType, FILING_TYPES, "ALL");
    String st = normalizeOptional(status, STATUSES, "ALL");
    int y = year == null ? LocalDate.now(clock.withZone(IST)).getYear() : year;
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? 20 : Math.min(100, Math.max(1, limit));
    boolean archived = Boolean.TRUE.equals(includeArchived);
    ListPage result =
        store.list(new ListFilter(typeEqualsAll(type), stEqualsAll(st), y, archived, p, l));
    List<Map<String, Object>> filings = new ArrayList<>();
    for (ComplianceFiling f : result.filings()) {
      filings.add(toApi(f));
    }
    Map<String, Object> kpis = new LinkedHashMap<>();
    kpis.put("pending_filings", result.pending());
    kpis.put("overdue_filings", result.overdue());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpis", kpis);
    data.put("filings", filings);
    data.put("total_pages", (result.total() + l - 1) / l);
    return new ListResult(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional
  public Map<String, Object> startGenerate(
      MedmatePrincipal principal, UUID filingId, String period, String format) {
    requireRole(principal, FILING_MUTATE);
    rateLimit("cfile:gen:" + principal.subject(), 5, 60);
    ComplianceFiling filing = requireFiling(filingId);
    String fmt = requireFormat(format);
    YearMonth ym = parsePeriod(period);
    YearMonth filingYm = YearMonth.from(filing.periodFrom());
    if (!ym.equals(filingYm)) {
      throw new AppException("VALIDATION_ERROR", "period must match filing period", 422);
    }
    GenerateJob existing = store.findGeneratingJob(filingId).orElse(null);
    if (existing != null) {
      return generateAccepted(existing, filingId, fmt);
    }
    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    GenerateJob job =
        new GenerateJob(
            jobId,
            filingId,
            fmt,
            "GENERATING",
            null,
            null,
            principal.subject(),
            null,
            null,
            null,
            now);
    try {
      store.insertGenerateJob(job);
    } catch (DuplicateKeyException ex) {
      GenerateJob raced = store.findGeneratingJob(filingId).orElse(job);
      return generateAccepted(raced, filingId, raced.format());
    }
    // ponytail: generate inline (no SQS yet); poll returns READY + 24h URL
    completeGenerateJob(jobId, principal);
    return generateAccepted(job, filingId, fmt);
  }

  @Transactional
  public Map<String, Object> pollGenerate(MedmatePrincipal principal, UUID filingId, UUID jobId) {
    requireRole(principal, FILING_MUTATE);
    rateLimit("cfile:gen-poll:" + principal.subject(), 60, 60);
    GenerateJob job =
        store
            .findGenerateJob(jobId)
            .orElseThrow(() -> new AppException("NOT_FOUND", "Generate job not found", 404));
    if (!job.filingId().equals(filingId)) {
      throw new AppException("NOT_FOUND", "Generate job not found", 404);
    }
    if ("GENERATING".equals(job.status())) {
      completeGenerateJob(jobId, principal);
      job =
          store
              .findGenerateJob(jobId)
              .orElseThrow(() -> new AppException("NOT_FOUND", "Generate job not found", 404));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", job.id());
    data.put("status", job.status());
    if ("READY".equals(job.status()) && job.storageKey() != null) {
      Instant expiresAt =
          job.expiresAt() != null ? job.expiresAt() : clock.instant().plus(DOWNLOAD_TTL);
      data.put("download_url", exportStore.createDownloadUrl(job.storageKey(), DOWNLOAD_TTL));
      data.put("row_count", job.rowCount());
      data.put("generated_at", job.generatedAt());
      data.put("expires_at", expiresAt);
    } else if ("FAILED".equals(job.status())) {
      data.put("error_message", job.errorMessage());
    }
    return data;
  }

  @Transactional
  public Map<String, Object> markFiled(
      MedmatePrincipal principal,
      UUID filingId,
      UUID filedBy,
      Instant filedAt,
      String referenceNumber) {
    requireRole(principal, FILING_MUTATE);
    rateLimit("cfile:mark:" + principal.subject(), 10, 60);
    if (referenceNumber == null || referenceNumber.isBlank()) {
      throw new AppException("REFERENCE_NUMBER_REQUIRED", "reference_number is required", 422);
    }
    if (filedBy == null) {
      throw new AppException("VALIDATION_ERROR", "filed_by is required", 422);
    }
    if (filedAt == null) {
      throw new AppException("VALIDATION_ERROR", "filed_at is required", 422);
    }
    ComplianceFiling filing = requireFiling(filingId);
    if ("FILED".equals(filing.status())) {
      throw new AppException("FILING_ALREADY_FILED", "Filing already marked as FILED", 409);
    }
    Instant now = clock.instant();
    ComplianceFiling updated =
        new ComplianceFiling(
            filing.id(),
            filing.filingType(),
            filing.periodFrom(),
            filing.periodTo(),
            filing.dueDate(),
            "FILED",
            filing.generatedReportS3Key(),
            filing.generatedReportFormat(),
            filing.generatedAt(),
            filedBy,
            filedAt,
            referenceNumber.trim(),
            filing.archived(),
            filing.overdueAlertedAt(),
            filing.overdueEscalationAt(),
            filing.createdAt(),
            now);
    store.update(updated);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reference_number", referenceNumber.trim());
    payload.put("filed_by", filedBy.toString());
    payload.put("filed_at", filedAt.toString());
    appendActivity(filingId, "FILING_MARKED", principal, payload);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("filing_id", filingId);
    data.put("status", "FILED");
    data.put("filed_by", filedBy);
    data.put("filed_at", filedAt);
    data.put("reference_number", referenceNumber.trim());
    return data;
  }

  @Transactional(readOnly = true)
  public ActivityResult listActivity(
      MedmatePrincipal principal,
      String action,
      UUID actorId,
      String fromDate,
      String toDate,
      Integer page,
      Integer limit) {
    requireRole(principal, ACTIVITY_READ);
    rateLimit("cfile:alog:" + principal.subject(), 30, 60);
    String act = normalizeOptional(action, ACTIVITY_ACTIONS, "ALL");
    LocalDate today = LocalDate.now(clock.withZone(IST));
    LocalDate from =
        fromDate == null || fromDate.isBlank()
            ? today.minusDays(30)
            : parseDate(fromDate, "from_date");
    LocalDate to = toDate == null || toDate.isBlank() ? today : parseDate(toDate, "to_date");
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? 50 : Math.min(200, Math.max(1, limit));
    Instant fromInst = from.atStartOfDay(IST).toInstant();
    Instant toEx = to.plusDays(1).atStartOfDay(IST).toInstant();
    ActivityPage result =
        store.listActivity(new ActivityFilter(typeEqualsAll(act), actorId, fromInst, toEx, p, l));
    return new ActivityResult(result.items(), PaginationMeta.of(p, l, result.total()));
  }

  @Transactional
  public Map<String, Object> initiateDrugRecall(
      MedmatePrincipal principal, String drugName, String batchNo, String reason) {
    requireRole(principal, FILING_MUTATE);
    rateLimit("cfile:recall:" + principal.subject(), 10, 60);
    if (drugName == null || drugName.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "drug_name is required", 422);
    }
    if (batchNo == null || batchNo.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "batch_no is required", 422);
    }
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock.withZone(IST));
    InventoryBanPort.BanResult ban =
        inventoryBan.banByDrugNameAndBatch(drugName.trim(), batchNo.trim());
    UUID filingId = Ids.newId();
    ComplianceFiling filing =
        new ComplianceFiling(
            filingId,
            "DRUG_RECALL",
            today,
            today,
            today,
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            now,
            now);
    store.insert(filing);
    for (UUID pharmacyId : ban.pharmacyIds()) {
      notifications.notifyPharmacyDrugRecall(pharmacyId, drugName.trim(), batchNo.trim());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("drug_name", drugName.trim());
    payload.put("batch_no", batchNo.trim());
    payload.put("pharmacies_affected", ban.pharmacyIds().size());
    payload.put("batches_banned", ban.batchesBanned());
    if (reason != null && !reason.isBlank()) {
      payload.put("reason", reason.trim());
    }
    appendActivity(filingId, "DRUG_RECALLED", principal, payload);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("filing_id", filingId);
    data.put("drug_name", drugName.trim());
    data.put("batch_no", batchNo.trim());
    data.put("batches_banned", ban.batchesBanned());
    data.put("pharmacies_affected", ban.pharmacyIds().size());
    data.put("pharmacy_ids", ban.pharmacyIds());
    return data;
  }

  /** Monthly: create prior-month H1 + X filings (cron 0 9 1 * * IST). */
  @Transactional
  public int createMonthlyFilings() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    YearMonth prior = YearMonth.from(today).minusMonths(1);
    LocalDate periodFrom = prior.atDay(1);
    LocalDate periodTo = prior.atEndOfMonth();
    LocalDate due = nextBusinessDay(YearMonth.from(today).atDay(15));
    Instant now = clock.instant();
    int created = 0;
    for (String type : List.of("SCHEDULE_H1_REGISTER", "SCHEDULE_X_REGISTER")) {
      if (store.existsTypePeriod(type, periodFrom, periodTo)) {
        continue;
      }
      store.insert(
          new ComplianceFiling(
              Ids.newId(),
              type,
              periodFrom,
              periodTo,
              due,
              "PENDING",
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              null,
              null,
              now,
              now));
      created++;
    }
    return created;
  }

  /** Daily: PENDING past due → OVERDUE + email; 3-day escalation. */
  @Transactional
  public int processOverdueFilings() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    Instant now = clock.instant();
    int marked = 0;
    for (ComplianceFiling f : store.findPendingPastDue(today)) {
      store.update(
          new ComplianceFiling(
              f.id(),
              f.filingType(),
              f.periodFrom(),
              f.periodTo(),
              f.dueDate(),
              "OVERDUE",
              f.generatedReportS3Key(),
              f.generatedReportFormat(),
              f.generatedAt(),
              f.filedBy(),
              f.filedAt(),
              f.referenceNumber(),
              f.archived(),
              now,
              f.overdueEscalationAt(),
              f.createdAt(),
              now));
      notifications.notifyComplianceFilingOverdue(f.id(), f.filingType(), false);
      marked++;
    }
    for (ComplianceFiling f : store.findOverdueForEscalation(today.minusDays(3))) {
      if (f.overdueEscalationAt() != null) {
        continue;
      }
      store.setOverdueEscalation(f.id(), now);
      notifications.notifyComplianceFilingOverdue(f.id(), f.filingType(), true);
    }
    return marked;
  }

  /** Yearly: archive filings with period_to older than 5 years. */
  @Transactional
  public int archiveOldFilings() {
    LocalDate cutoff = LocalDate.now(clock.withZone(IST)).minusYears(5);
    return store.archiveOlderThan(cutoff, clock.instant());
  }

  static LocalDate nextBusinessDay(LocalDate date) {
    LocalDate d = date;
    while (isNonBusinessDay(d)) {
      d = d.plusDays(1);
    }
    return d;
  }

  static boolean isNonBusinessDay(LocalDate date) {
    if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
      return true;
    }
    int mmdd = date.getMonthValue() * 100 + date.getDayOfMonth();
    return FIXED_HOLIDAYS_MMDD.contains(mmdd);
  }

  private void completeGenerateJob(UUID jobId, MedmatePrincipal principal) {
    GenerateJob job = store.findGenerateJob(jobId).orElse(null);
    if (job == null || !"GENERATING".equals(job.status())) {
      return;
    }
    ComplianceFiling filing = store.findById(job.filingId()).orElse(null);
    if (filing == null) {
      store.updateGenerateJob(
          new GenerateJob(
              job.id(),
              job.filingId(),
              job.format(),
              "FAILED",
              null,
              null,
              job.requestedBy(),
              null,
              null,
              "Filing not found",
              job.createdAt()));
      return;
    }
    try {
      String schedule = scheduleForFiling(filing.filingType());
      List<ScheduleDrugRegisterEntry> rows;
      if (schedule == null) {
        rows = List.of();
      } else {
        Instant from = filing.periodFrom().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toEx = filing.periodTo().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        rows =
            registerStore.listAll(
                new ScheduleDrugRegisterStore.ListFilter(
                    schedule, null, null, from, toEx, 1, 100_000));
      }
      String csv = ScheduleDrugRegisterService.buildRegulatoryCsv(rows);
      byte[] bytes;
      String contentType;
      String ext;
      if ("PDF".equals(job.format())) {
        bytes = minimalPdf(csv);
        contentType = "application/pdf";
        ext = ".pdf";
      } else {
        bytes = csv.getBytes(StandardCharsets.UTF_8);
        contentType = "text/csv";
        ext = ".csv";
      }
      String key =
          StorageObjectKeys.key(
              StorageObjectKeys.REPORTS,
              "compliance-"
                  + filing.filingType().toLowerCase(Locale.ROOT)
                  + "-"
                  + filing.periodFrom()
                  + "-"
                  + job.id()
                  + ext);
      exportStore.put(key, bytes, contentType);
      Instant generatedAt = clock.instant();
      Instant expiresAt = generatedAt.plus(DOWNLOAD_TTL);
      store.updateGenerateJob(
          new GenerateJob(
              job.id(),
              job.filingId(),
              job.format(),
              "READY",
              key,
              rows.size(),
              job.requestedBy(),
              generatedAt,
              expiresAt,
              null,
              job.createdAt()));
      store.update(
          new ComplianceFiling(
              filing.id(),
              filing.filingType(),
              filing.periodFrom(),
              filing.periodTo(),
              filing.dueDate(),
              filing.status(),
              key,
              job.format(),
              generatedAt,
              filing.filedBy(),
              filing.filedAt(),
              filing.referenceNumber(),
              filing.archived(),
              filing.overdueAlertedAt(),
              filing.overdueEscalationAt(),
              filing.createdAt(),
              generatedAt));
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("format", job.format());
      payload.put("row_count", rows.size());
      payload.put("job_id", job.id().toString());
      appendActivity(filing.id(), "FILING_GENERATED", principal, payload);
    } catch (RuntimeException ex) {
      store.updateGenerateJob(
          new GenerateJob(
              job.id(),
              job.filingId(),
              job.format(),
              "FAILED",
              null,
              null,
              job.requestedBy(),
              null,
              null,
              ex.getMessage() == null ? "generate failed" : ex.getMessage(),
              job.createdAt()));
    }
  }

  private static String scheduleForFiling(String filingType) {
    if ("SCHEDULE_H1_REGISTER".equals(filingType)) {
      return "H1";
    }
    if ("SCHEDULE_X_REGISTER".equals(filingType)) {
      return "X";
    }
    return null;
  }

  /** Minimal valid-enough PDF wrapping report text (ponytail until template engine). */
  static byte[] minimalPdf(String body) {
    String safe = body == null ? "" : body.replace('\\', ' ').replace('(', ' ').replace(')', ' ');
    if (safe.length() > 8000) {
      safe = safe.substring(0, 8000);
    }
    String content = "BT /F1 10 Tf 50 750 Td (" + safe.replace("\n", ") Tj T* (") + ") Tj ET";
    StringBuilder pdf = new StringBuilder();
    pdf.append("%PDF-1.4\n");
    pdf.append("1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n");
    pdf.append("2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n");
    pdf.append("3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ");
    pdf.append("/Contents 4 0 R /Resources<< /Font<< /F1 5 0 R >> >> >>endobj\n");
    pdf.append("4 0 obj<< /Length ").append(content.length()).append(" >>stream\n");
    pdf.append(content).append("\nendstream\nendobj\n");
    pdf.append("5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n");
    pdf.append("xref\n0 6\n0000000000 65535 f \n");
    pdf.append("trailer<< /Size 6 /Root 1 0 R >>\nstartxref\n0\n%%EOF\n");
    return pdf.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private Map<String, Object> generateAccepted(GenerateJob job, UUID filingId, String format) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", job.id());
    data.put("filing_id", filingId);
    data.put("status", "GENERATING");
    data.put("format", format);
    data.put("estimated_ready_seconds", 20);
    data.put("poll_url", "/api/v1/admin/compliance/filings/" + filingId + "/generate/" + job.id());
    return data;
  }

  private Map<String, Object> toApi(ComplianceFiling f) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("filing_id", f.id());
    m.put("filing_type", f.filingType());
    m.put(
        "period_label",
        f.periodFrom().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)));
    m.put("period_from", f.periodFrom().toString());
    m.put("period_to", f.periodTo().toString());
    m.put("due_date", f.dueDate().toString());
    m.put("status", f.status());
    m.put("filed_at", f.filedAt());
    m.put("filed_by", f.filedBy());
    m.put("reference_number", f.referenceNumber());
    String url = null;
    if (f.generatedReportS3Key() != null) {
      url = exportStore.createDownloadUrl(f.generatedReportS3Key(), DOWNLOAD_TTL);
    }
    m.put("generated_report_url", url);
    m.put("is_archived", f.archived());
    return m;
  }

  private void appendActivity(
      UUID filingId, String action, MedmatePrincipal principal, Map<String, Object> payload) {
    String json;
    try {
      json = objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      json = "{}";
    }
    store.appendActivity(
        Ids.newId(),
        null,
        null,
        filingId,
        action,
        principal.subject(),
        principal.role().value(),
        json,
        null,
        clock.instant());
  }

  private ComplianceFiling requireFiling(UUID filingId) {
    return store
        .findById(filingId)
        .orElseThrow(() -> new AppException("FILING_NOT_FOUND", "Filing ID not found", 404));
  }

  private static String requireFormat(String format) {
    if (format == null || format.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "format is required", 422);
    }
    String f = format.trim().toUpperCase(Locale.ROOT);
    if (!FORMATS.contains(f)) {
      throw new AppException("VALIDATION_ERROR", "format must be CSV or PDF", 422);
    }
    return f;
  }

  private static YearMonth parsePeriod(String period) {
    if (period == null || period.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "period is required", 422);
    }
    try {
      return YearMonth.parse(period.trim());
    } catch (DateTimeParseException ex) {
      throw new AppException("VALIDATION_ERROR", "period must be YYYY-MM", 422);
    }
  }

  private static LocalDate parseDate(String raw, String field) {
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", field + " must be ISO date", 400);
    }
  }

  private static String normalizeOptional(String raw, Set<String> allowed, String allToken) {
    if (raw == null || raw.isBlank() || allToken.equalsIgnoreCase(raw.trim())) {
      return allToken;
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(v)) {
      throw new AppException("VALIDATION_ERROR", "Invalid filter value: " + raw, 422);
    }
    return v;
  }

  private static String typeEqualsAll(String v) {
    return "ALL".equals(v) ? null : v;
  }

  private static String stEqualsAll(String v) {
    return "ALL".equals(v) ? null : v;
  }

  private void requireRole(MedmatePrincipal principal, Set<AuthRole> allowed) {
    if (principal == null || !allowed.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429, windowSeconds);
    }
  }
}
