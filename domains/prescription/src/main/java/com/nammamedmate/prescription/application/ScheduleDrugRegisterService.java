package com.nammamedmate.prescription.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.InventoryBatchPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ExportJob;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ListFilter;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ListPage;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.PharmacySnapshot;
import com.nammamedmate.prescription.application.port.out.ScheduleRegisterWritePort;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.ScheduleDrugRegisterEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleDrugRegisterService implements ScheduleRegisterWritePort {

  private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(15);
  private static final Set<AuthRole> ADMIN_VIEW =
      Set.of(AuthRole.ADMIN_COMPLIANCE, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Set<AuthRole> ADMIN_EXPORT =
      Set.of(AuthRole.ADMIN_COMPLIANCE, AuthRole.ADMIN_SUPER);
  private static final Set<AuthRole> RETENTION_ROLES =
      Set.of(AuthRole.ADMIN_COMPLIANCE, AuthRole.ADMIN_SUPER, AuthRole.PHARMACY_OWNER);
  private static final Set<AuthRole> PHARMACY_ROLES =
      Set.of(AuthRole.PHARMACY_OWNER, AuthRole.PHARMACY_STAFF);

  static final String CSV_HEADER =
      "S.No,Date,Rx_Reference_No,Patient_Name,Patient_Age,Prescriber_Name,Prescriber_Reg_No,"
          + "Drug_Name,Batch_No,Quantity_Issued,Running_Balance,Pharmacy_License_No,Dispensed_By";

  private final ScheduleDrugRegisterStore store;
  private final PrescriptionStore prescriptionStore;
  private final CatalogueSchedulePort catalogueSchedule;
  private final DoctorCardPort doctorCards;
  private final InventoryBatchPort inventoryBatch;
  private final ComplianceExportStore exportStore;
  private final DoctorRegistryService doctorRegistry;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public ScheduleDrugRegisterService(
      ScheduleDrugRegisterStore store,
      PrescriptionStore prescriptionStore,
      CatalogueSchedulePort catalogueSchedule,
      DoctorCardPort doctorCards,
      InventoryBatchPort inventoryBatch,
      ComplianceExportStore exportStore,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        store,
        prescriptionStore,
        catalogueSchedule,
        doctorCards,
        inventoryBatch,
        exportStore,
        null,
        rateLimiter,
        clock);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScheduleDrugRegisterService(
      ScheduleDrugRegisterStore store,
      PrescriptionStore prescriptionStore,
      CatalogueSchedulePort catalogueSchedule,
      DoctorCardPort doctorCards,
      InventoryBatchPort inventoryBatch,
      ComplianceExportStore exportStore,
      DoctorRegistryService doctorRegistry,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.prescriptionStore = prescriptionStore;
    this.catalogueSchedule = catalogueSchedule;
    this.doctorCards = doctorCards;
    this.inventoryBatch = inventoryBatch;
    this.exportStore = exportStore;
    this.doctorRegistry = doctorRegistry;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Override
  @Transactional
  public void recordDispense(
      UUID pharmacyId, UUID rxId, UUID staffId, List<ApprovedMedicine> medicines) {
    if (pharmacyId == null || rxId == null || medicines == null || medicines.isEmpty()) {
      return;
    }
    PrescriptionRecord rx =
        prescriptionStore
            .findById(rxId)
            .orElseThrow(() -> new AppException("RX_NOT_FOUND", "Prescription not found", 404));
    PharmacySnapshot pharmacy =
        store
            .pharmacy(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    String license =
        pharmacy.licenseNo() == null || pharmacy.licenseNo().isBlank()
            ? "UNKNOWN"
            : pharmacy.licenseNo();
    String staffName = store.staffName(staffId).orElse("Pharmacist");
    UUID orderId = store.orderIdForRx(rxId, pharmacyId).orElse(rx.associatedOrderId());
    DoctorCardPort.DoctorCard doctor =
        doctorCards
            .findForPrescription(rxId, rx.type(), rx.doctorName(), rx.teleconsultId())
            .orElse(null);
    String prescriberName;
    if (doctor != null && doctor.name() != null && !doctor.name().isBlank()) {
      prescriberName = doctor.name();
    } else if (rx.doctorName() != null && !rx.doctorName().isBlank()) {
      prescriberName = rx.doctorName();
    } else {
      prescriberName = "Unknown";
    }
    String prescriberReg = "UNKNOWN";
    if (doctor != null && doctor.registrationNo() != null && !doctor.registrationNo().isBlank()) {
      prescriberReg = doctor.registrationNo();
    }
    String patientName = "Unknown";
    if (rx.patientName() != null && !rx.patientName().isBlank()) {
      patientName = rx.patientName();
    }
    Instant now = clock.instant();
    int year = now.atZone(ZoneOffset.UTC).getYear();
    boolean recordedScheduled = false;

    for (ApprovedMedicine med : medicines) {
      if (med == null) {
        continue;
      }
      if (med.name() == null || med.name().isBlank() || med.quantity() <= 0) {
        continue;
      }
      String schedule = resolveSchedule(med);
      if (!ScheduleDrugRegisterEntry.isRegisterSchedule(schedule)) {
        continue;
      }
      schedule = schedule.toUpperCase(Locale.ROOT);
      int previous =
          store
              .latestRunningBalance(pharmacyId, schedule, med.name())
              .orElseGet(
                  () ->
                      inventoryBatch
                          .findOpeningStock(pharmacyId, med.name())
                          .map(InventoryBatchPort.OpeningStock::quantity)
                          .orElse(0));
      int running = previous - med.quantity();
      String batch =
          inventoryBatch
              .findOpeningStock(pharmacyId, med.name())
              .map(InventoryBatchPort.OpeningStock::batchNo)
              .orElse(null);
      int sno = store.nextSno(pharmacyId, schedule);
      int seq = store.nextRxSeq(pharmacyId, year);
      String rxRef = String.format(Locale.ROOT, "RX-%d-%05d", year, seq);
      Period retention = ScheduleDrugRegisterEntry.retentionFor(schedule);
      Instant retentionExpires =
          ZonedDateTime.ofInstant(now, ZoneOffset.UTC).plus(retention).toInstant();
      String unit = inferUnit(med.name());
      ScheduleDrugRegisterEntry entry =
          new ScheduleDrugRegisterEntry(
              Ids.newId(),
              sno,
              pharmacyId,
              schedule,
              rxId,
              rxRef,
              orderId,
              patientName,
              null,
              prescriberName,
              prescriberReg,
              med.name(),
              batch,
              med.quantity(),
              unit,
              running,
              license,
              staffName,
              staffId,
              now,
              retentionExpires,
              false,
              now);
      store.insert(entry);
      recordedScheduled = true;
    }
    if (recordedScheduled && doctorRegistry != null) {
      doctorRegistry.recordScheduledDrug(rxId);
    }
  }

  @Transactional(readOnly = true)
  public ListResult listAdmin(
      MedmatePrincipal principal,
      String schedule,
      UUID pharmacyId,
      String drugName,
      String fromDate,
      String toDate,
      Integer page,
      Integer limit,
      Boolean export) {
    requireAdminView(principal);
    rateLimit("dreg:admin:" + principal.subject(), 60, 60);
    String sch = requireSchedule(schedule);
    if (Boolean.TRUE.equals(export)) {
      Map<String, Object> exported = syncExportCsv(sch, pharmacyId, drugName, fromDate, toDate);
      return new ListResult(exported, PaginationMeta.of(1, 1, 1));
    }
    return listInternal(sch, pharmacyId, drugName, fromDate, toDate, page, limit);
  }

  @Transactional(readOnly = true)
  public ListResult listPharmacy(
      MedmatePrincipal principal,
      String schedule,
      String drugName,
      String fromDate,
      String toDate,
      Integer page,
      Integer limit,
      Boolean export) {
    UUID pharmacyId = requirePharmacy(principal);
    rateLimit("dreg:pharm:" + pharmacyId, 30, 60);
    String sch = requireSchedule(schedule);
    if (Boolean.TRUE.equals(export)) {
      Map<String, Object> exported = syncExportCsv(sch, pharmacyId, drugName, fromDate, toDate);
      return new ListResult(exported, PaginationMeta.of(1, 1, 1));
    }
    return listInternal(sch, pharmacyId, drugName, fromDate, toDate, page, limit);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> retentionRules(MedmatePrincipal principal) {
    if (principal == null || !RETENTION_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    rateLimit("dreg:retention:" + principal.subject(), 10, 60);
    List<Map<String, Object>> rules = new ArrayList<>();
    rules.add(
        rule(
            "H1",
            3,
            "Drugs and Cosmetics Rules 1945, Rule 65(15)",
            "https://cdsco.gov.in/opencms/export/sites/CDSCO_WEB/Pdf-documents/Schedule-H1.pdf",
            "Register must be maintained in Form-17B and produced on demand by the Inspector"));
    rules.add(
        rule(
            "X",
            5,
            "Drugs and Cosmetics Rules 1945, Rule 65(16)",
            "https://cdsco.gov.in/opencms/export/sites/CDSCO_WEB/Pdf-documents/Schedule-X.pdf",
            "Narcotic/psychotropic drugs; register form specified under NDPS Act provisions"));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rules", rules);
    data.put(
        "archival_policy",
        "Entries are archived (not deleted) after retention period. Archived entries remain"
            + " accessible via API with is_archived: true.");
    return data;
  }

  @Transactional
  public Map<String, Object> createExportJob(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String schedule,
      String fromDate,
      String toDate) {
    requireAdminExport(principal);
    rateLimit("dreg:export:" + principal.subject(), 5, 60);
    if (pharmacyId == null || !store.pharmacyExists(pharmacyId)) {
      throw new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404);
    }
    String sch = requireSchedule(schedule);
    LocalDate from = parseRequiredDate(fromDate, "from_date");
    LocalDate to = parseRequiredDate(toDate, "to_date");
    if (to.isBefore(from)) {
      throw new AppException("VALIDATION_ERROR", "to_date must be on or after from_date", 400);
    }
    // Inclusive range may span at most 1 year (e.g. 2026-01-01 .. 2026-12-31).
    if (to.isAfter(from.plusYears(1).minusDays(1))) {
      throw new AppException("DATE_RANGE_TOO_LARGE", "Date range exceeds 1 year", 422);
    }
    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    ExportJob job =
        new ExportJob(
            jobId,
            pharmacyId,
            sch,
            from,
            to,
            "GENERATING",
            null,
            null,
            principal.subject(),
            null,
            null,
            null,
            now);
    store.insertExportJob(job);
    // ponytail: generate inline (no SQS yet); poll still returns READY + 15m signed URL
    completeExportJob(jobId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("export_job_id", jobId);
    data.put("status", "GENERATING");
    data.put("estimated_ready_seconds", 15);
    data.put("poll_url", "/api/v1/admin/compliance/drug-register/export/" + jobId);
    return data;
  }

  @Transactional
  public Map<String, Object> pollExportJob(MedmatePrincipal principal, UUID jobId) {
    requireAdminExport(principal);
    rateLimit("dreg:export-poll:" + principal.subject(), 60, 60);
    ExportJob job =
        store
            .findExportJob(jobId)
            .orElseThrow(() -> new AppException("NOT_FOUND", "Export job not found", 404));
    if ("GENERATING".equals(job.status())) {
      completeExportJob(jobId);
      job =
          store
              .findExportJob(jobId)
              .orElseThrow(() -> new AppException("NOT_FOUND", "Export job not found", 404));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("export_job_id", job.id());
    data.put("status", job.status());
    if ("READY".equals(job.status()) && job.storageKey() != null) {
      Instant expiresAt = clock.instant().plus(DOWNLOAD_TTL);
      String url = exportStore.createDownloadUrl(job.storageKey(), DOWNLOAD_TTL);
      data.put("download_url", url);
      data.put("row_count", job.rowCount());
      data.put("generated_at", job.generatedAt());
      data.put("expires_at", expiresAt);
    } else if ("FAILED".equals(job.status())) {
      data.put("error_message", job.errorMessage());
    }
    return data;
  }

  @Transactional
  public int archiveExpired() {
    return store.markArchivedPastRetention(clock.instant());
  }

  private void completeExportJob(UUID jobId) {
    ExportJob job = store.findExportJob(jobId).orElse(null);
    if (job == null || !"GENERATING".equals(job.status())) {
      return;
    }
    try {
      Instant from = job.fromDate().atStartOfDay(ZoneOffset.UTC).toInstant();
      Instant toExclusive = job.toDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
      ListFilter filter =
          new ListFilter(job.schedule(), job.pharmacyId(), null, from, toExclusive, 1, 100_000);
      List<ScheduleDrugRegisterEntry> rows = store.listAll(filter);
      String key =
          StorageObjectKeys.export(
              "drug-register-"
                  + job.schedule()
                  + "-"
                  + job.pharmacyId()
                  + "-"
                  + job.fromDate()
                  + ".csv");
      byte[] csv = buildRegulatoryCsv(rows).getBytes(StandardCharsets.UTF_8);
      exportStore.put(key, csv, "text/csv");
      Instant generatedAt = clock.instant();
      ExportJob ready =
          new ExportJob(
              job.id(),
              job.pharmacyId(),
              job.schedule(),
              job.fromDate(),
              job.toDate(),
              "READY",
              key,
              rows.size(),
              job.requestedBy(),
              generatedAt,
              generatedAt.plus(DOWNLOAD_TTL),
              null,
              job.createdAt());
      store.updateExportJob(ready);
    } catch (RuntimeException ex) {
      ExportJob failed =
          new ExportJob(
              job.id(),
              job.pharmacyId(),
              job.schedule(),
              job.fromDate(),
              job.toDate(),
              "FAILED",
              null,
              null,
              job.requestedBy(),
              null,
              null,
              ex.getMessage() == null ? "export failed" : ex.getMessage(),
              job.createdAt());
      store.updateExportJob(failed);
    }
  }

  private Map<String, Object> syncExportCsv(
      String schedule, UUID pharmacyId, String drugName, String fromDate, String toDate) {
    LocalDate from =
        fromDate == null || fromDate.isBlank()
            ? LocalDate.of(1970, 1, 1)
            : parseRequiredDate(fromDate, "from_date");
    LocalDate to =
        toDate == null || toDate.isBlank()
            ? LocalDate.now(clock)
            : parseRequiredDate(toDate, "to_date");
    Instant fromInst = from.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant toEx = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    ListFilter filter =
        new ListFilter(schedule, pharmacyId, blankToNull(drugName), fromInst, toEx, 1, 100_000);
    List<ScheduleDrugRegisterEntry> rows = store.listAll(filter);
    Instant generatedAt = clock.instant();
    String key = StorageObjectKeys.export("drug-register-" + Ids.newId() + ".csv");
    exportStore.put(key, buildRegulatoryCsv(rows).getBytes(StandardCharsets.UTF_8), "text/csv");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("download_url", exportStore.createDownloadUrl(key, DOWNLOAD_TTL));
    data.put("expires_at", generatedAt.plus(DOWNLOAD_TTL));
    data.put("record_count", rows.size());
    data.put("generated_at", generatedAt);
    return data;
  }

  private ListResult listInternal(
      String schedule,
      UUID pharmacyId,
      String drugName,
      String fromDate,
      String toDate,
      Integer page,
      Integer limit) {
    int p = 1;
    if (page != null && page >= 1) {
      p = page;
    }
    int l = 50;
    if (limit != null) {
      l = Math.min(500, Math.max(1, limit));
    }
    Instant from = null;
    if (fromDate != null && !fromDate.isBlank()) {
      from = parseRequiredDate(fromDate, "from_date").atStartOfDay(ZoneOffset.UTC).toInstant();
    }
    Instant toEx = null;
    if (toDate != null && !toDate.isBlank()) {
      toEx =
          parseRequiredDate(toDate, "to_date").plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
    ListFilter filter =
        new ListFilter(schedule, pharmacyId, blankToNull(drugName), from, toEx, p, l);
    ListPage pageResult = store.list(filter);
    List<Map<String, Object>> entries = new ArrayList<>();
    for (ScheduleDrugRegisterEntry e : pageResult.entries()) {
      entries.add(toApi(e));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schedule", schedule);
    data.put("entries", entries);
    data.put("total_qty_issued", pageResult.totalQtyIssued());
    data.put("total_pages", (pageResult.total() + l - 1) / l);
    return new ListResult(data, PaginationMeta.of(p, l, pageResult.total()));
  }

  private Map<String, Object> toApi(ScheduleDrugRegisterEntry e) {
    PharmacySnapshot ph = store.pharmacy(e.pharmacyId()).orElse(null);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("entry_id", e.id());
    m.put("sno", e.sno());
    m.put("date", e.dispensedAt().atZone(ZoneOffset.UTC).toLocalDate().toString());
    m.put("rx_reference_no", e.rxReferenceNo());
    m.put("patient_name", e.patientName());
    m.put("patient_age", e.patientAge());
    m.put("prescriber_name", e.prescriberName());
    m.put("prescriber_reg_no", e.prescriberRegNo());
    m.put("drug_name", e.drugName());
    m.put("batch_no", e.batchNo());
    m.put("quantity_issued", e.quantityIssued());
    m.put("running_balance", e.runningBalance());
    m.put("pharmacy_name", ph == null ? null : ph.name());
    m.put("pharmacy_license_no", e.pharmacyLicenseNo());
    m.put("dispensed_by", e.dispensedByName());
    m.put("dispensed_at", e.dispensedAt());
    m.put("is_archived", e.archived());
    m.put("retention_expires_at", e.retentionExpiresAt());
    return m;
  }

  static String buildRegulatoryCsv(List<ScheduleDrugRegisterEntry> rows) {
    StringBuilder sb = new StringBuilder();
    sb.append(CSV_HEADER).append('\n');
    for (ScheduleDrugRegisterEntry e : rows) {
      sb.append(e.sno()).append(',');
      sb.append(csv(e.dispensedAt().atZone(ZoneOffset.UTC).toLocalDate().toString())).append(',');
      sb.append(csv(e.rxReferenceNo())).append(',');
      sb.append(csv(e.patientName())).append(',');
      sb.append(e.patientAge() == null ? "" : e.patientAge()).append(',');
      sb.append(csv(e.prescriberName())).append(',');
      sb.append(csv(e.prescriberRegNo())).append(',');
      sb.append(csv(e.drugName())).append(',');
      sb.append(csv(e.batchNo())).append(',');
      sb.append(e.quantityIssued()).append(',');
      sb.append(e.runningBalance()).append(',');
      sb.append(csv(e.pharmacyLicenseNo())).append(',');
      sb.append(csv(e.dispensedByName())).append('\n');
    }
    return sb.toString();
  }

  private static String csv(String value) {
    String v = value == null ? "" : value;
    if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
      return "\"" + v.replace("\"", "\"\"") + "\"";
    }
    return v;
  }

  private String resolveSchedule(ApprovedMedicine med) {
    if (med.schedule() != null && !med.schedule().isBlank()) {
      String s = med.schedule().trim().toUpperCase(Locale.ROOT);
      if ("H1".equals(s) || "X".equals(s) || "H".equals(s) || "NONE".equals(s)) {
        return s;
      }
    }
    return catalogueSchedule.resolveSchedule(med.name()).orElse("NONE");
  }

  private static String inferUnit(String drugName) {
    String n = drugName.toUpperCase(Locale.ROOT);
    if (n.contains("ML") || n.contains("SYRUP")) {
      return "ML";
    }
    if (n.contains("CAPSULE")) {
      return "CAPSULES";
    }
    return "TABLETS";
  }

  private static Map<String, Object> rule(
      String schedule, int years, String governing, String url, String notes) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schedule", schedule);
    m.put("retention_years", years);
    m.put("governing_rule", governing);
    m.put("dcg_guideline_url", url);
    m.put("notes", notes);
    return m;
  }

  private static String requireSchedule(String schedule) {
    if (schedule == null || schedule.isBlank()) {
      return "ALL";
    }
    String s = schedule.trim().toUpperCase(Locale.ROOT);
    if ("ALL".equals(s)) {
      return "ALL";
    }
    if (!"H1".equals(s) && !"X".equals(s)) {
      throw new AppException("INVALID_SCHEDULE", "Schedule must be H1, X, or ALL", 422);
    }
    return s;
  }

  private static LocalDate parseRequiredDate(String raw, String field) {
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", field + " must be ISO date", 400);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private void requireAdminView(MedmatePrincipal principal) {
    if (principal == null || !ADMIN_VIEW.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private void requireAdminExport(MedmatePrincipal principal) {
    if (principal == null || !ADMIN_EXPORT.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private UUID requirePharmacy(MedmatePrincipal principal) {
    if (principal == null || !PHARMACY_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy context required", 403);
    }
    return principal.pharmacyId();
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429, windowSeconds);
    }
  }
}
