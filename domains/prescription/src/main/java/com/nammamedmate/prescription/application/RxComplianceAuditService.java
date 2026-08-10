package com.nammamedmate.prescription.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorAutoFlagPort;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore.Link;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.DispenseContext;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.DuplicateMatch;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListFilter;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListPage;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListRow;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.Stats;
import com.nammamedmate.prescription.domain.DoctorRecord;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RxComplianceAuditService implements DoctorAutoFlagPort {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Duration SIGNED_TTL = Duration.ofHours(1);
  private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);
  private static final Duration DUPLICATE_WINDOW = Duration.ofDays(30);
  private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
  private static final Set<String> SCHEDULES = Set.of("H", "H1", "X", "ALL");
  private static final Set<String> STATUSES =
      Set.of("AWAITING_AUDIT", "FLAGGED", "VERIFIED", "OVERDUE_AUDIT", "ALL");

  private final RxAuditStore auditStore;
  private final PrescriptionStore prescriptionStore;
  private final CatalogueSchedulePort catalogueSchedule;
  private final ComplianceExportStore exportStore;
  private final NotificationDispatchPort notifications;
  private final DoctorCardPort doctorCards;
  private final DoctorStore doctorStore;
  private final PresignedUrlService presigner;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public RxComplianceAuditService(
      RxAuditStore auditStore,
      PrescriptionStore prescriptionStore,
      CatalogueSchedulePort catalogueSchedule,
      ComplianceExportStore exportStore,
      NotificationDispatchPort notifications,
      DoctorCardPort doctorCards,
      PresignedUrlService presigner,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        auditStore,
        prescriptionStore,
        catalogueSchedule,
        exportStore,
        notifications,
        doctorCards,
        null,
        presigner,
        rateLimiter,
        clock);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public RxComplianceAuditService(
      RxAuditStore auditStore,
      PrescriptionStore prescriptionStore,
      CatalogueSchedulePort catalogueSchedule,
      ComplianceExportStore exportStore,
      NotificationDispatchPort notifications,
      DoctorCardPort doctorCards,
      DoctorStore doctorStore,
      PresignedUrlService presigner,
      RateLimiter rateLimiter,
      Clock clock) {
    this.auditStore = auditStore;
    this.prescriptionStore = prescriptionStore;
    this.catalogueSchedule = catalogueSchedule;
    this.exportStore = exportStore;
    this.notifications = notifications;
    this.doctorCards = doctorCards;
    this.doctorStore = doctorStore;
    this.presigner = presigner;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  /**
   * Called from pharmacy dispense when H/H1/X medicines are dispensed. Idempotent if audit already
   * exists for rx_id.
   */
  @Transactional
  public Optional<RxAuditEntry> createFromDispense(
      UUID rxId,
      UUID orderId,
      UUID pharmacyId,
      List<ApprovedMedicine> medicines,
      PrescriptionRecord rx,
      Instant dispensedAt) {
    if (auditStore.findByRxId(rxId).isPresent()) {
      return Optional.empty();
    }
    String schedule = resolveHighestSchedule(medicines, rx);
    if ("NONE".equals(schedule)) {
      return Optional.empty();
    }
    Instant now = dispensedAt == null ? clock.instant() : dispensedAt;
    Instant deadline = now.plus(RxAuditEntry.deadlineFor(schedule));
    DuplicateMatch dup = detectDuplicate(rx, medicines, now, rxId);
    RxAuditEntry entry =
        new RxAuditEntry(
            Ids.newId(),
            rxId,
            orderId,
            pharmacyId,
            schedule,
            "AWAITING_AUDIT",
            deadline,
            dup != null,
            dup == null ? null : dup.rxId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now);
    auditStore.insert(entry);
    applyDoctorAutoFlags(entry);
    return Optional.of(auditStore.findByRxId(rxId).orElse(entry));
  }

  /**
   * Before pharmacist review (enqueue) or when audit is created — auto-flag blacklisted /
   * unrecognised-qualification doctors.
   */
  @Override
  @Transactional
  public void applyPendingFlags(UUID rxId, UUID pharmacyId) {
    if (rxId == null || pharmacyId == null || doctorStore == null) {
      return;
    }
    Optional<RxAuditEntry> existing = auditStore.findByRxId(rxId);
    if (existing.isPresent()) {
      applyDoctorAutoFlags(existing.get());
      return;
    }
    Optional<Link> link = doctorStore.findLink(rxId);
    if (link.isEmpty()) {
      return;
    }
    Optional<DoctorRecord> doctor = doctorStore.findById(link.get().doctorId());
    if (doctor.isEmpty()) {
      return;
    }
    boolean blacklist = doctor.get().blacklisted() || link.get().pendingBlacklistFlag();
    boolean unrecognized = link.get().unrecognizedQualification();
    if (!blacklist && !unrecognized) {
      return;
    }
    Instant now = clock.instant();
    String reason = blacklist ? "BLACKLISTED_DOCTOR" : "UNRECOGNISED_QUALIFICATION";
    String severity = blacklist ? "HIGH" : "MEDIUM";
    String notes = unrecognized ? "UNRECOGNISED_QUALIFICATION" : null;
    PrescriptionRecord rx = prescriptionStore.findById(rxId).orElse(null);
    String schedule = rx == null ? "NONE" : resolveHighestSchedule(List.of(), rx);
    RxAuditEntry entry =
        new RxAuditEntry(
            Ids.newId(),
            rxId,
            null,
            pharmacyId,
            "NONE".equals(schedule) ? "NONE" : schedule,
            "FLAGGED",
            now.plus(RxAuditEntry.deadlineFor(schedule)),
            false,
            null,
            null,
            null,
            reason,
            severity,
            null,
            now,
            notes,
            now);
    auditStore.insert(entry);
  }

  private void applyDoctorAutoFlags(RxAuditEntry entry) {
    if (doctorStore == null) {
      return;
    }
    Optional<Link> link = doctorStore.findLink(entry.rxId());
    if (link.isEmpty()) {
      return;
    }
    Optional<DoctorRecord> doctor = doctorStore.findById(link.get().doctorId());
    boolean blacklist =
        (doctor.isPresent() && doctor.get().blacklisted()) || link.get().pendingBlacklistFlag();
    boolean unrecognized = link.get().unrecognizedQualification();
    if (!blacklist && !unrecognized) {
      return;
    }
    if ("FLAGGED".equals(entry.auditStatus())) {
      String fr = entry.flagReason();
      if ("BLACKLISTED_DOCTOR".equals(fr) || "UNRECOGNISED_QUALIFICATION".equals(fr)) {
        return;
      }
    }
    Instant now = clock.instant();
    String reason = blacklist ? "BLACKLISTED_DOCTOR" : "UNRECOGNISED_QUALIFICATION";
    String severity = blacklist ? "HIGH" : "MEDIUM";
    String notes = unrecognized ? "UNRECOGNISED_QUALIFICATION" : entry.notes();
    RxAuditEntry updated =
        new RxAuditEntry(
            entry.id(),
            entry.rxId(),
            entry.orderId(),
            entry.pharmacyId(),
            entry.schedule(),
            "FLAGGED",
            entry.auditDeadline(),
            entry.possibleDuplicate(),
            entry.possibleDuplicateRxId(),
            entry.verifiedBy(),
            entry.verifiedAt(),
            reason,
            severity,
            entry.flaggedBy(),
            now,
            notes,
            entry.createdAt());
    auditStore.update(updated);
  }

  @Transactional(readOnly = true)
  public ListResult list(
      MedmatePrincipal principal,
      String schedule,
      String status,
      String source,
      String fromDate,
      String toDate,
      String search,
      UUID pharmacyId,
      Integer page,
      Integer limit,
      Boolean export) {
    requireListAccess(principal);
    rateLimit("rxaudit:list:" + principal.subject(), 60, 60);
    if (Boolean.TRUE.equals(export)) {
      Map<String, Object> exported =
          exportCsv(principal, schedule, status, source, fromDate, toDate, search, pharmacyId);
      return new ListResult(exported, PaginationMeta.of(1, 1, 1));
    }
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? 20 : Math.min(200, Math.max(1, limit));
    ListFilter filter =
        new ListFilter(
            normalizeSchedule(schedule),
            normalizeStatus(status),
            normalizeSource(source),
            parseOptionalDate(fromDate, "from_date"),
            parseOptionalDate(toDate, "to_date"),
            blankToNull(search),
            pharmacyId,
            p,
            l);
    Instant now = clock.instant();
    ListPage pageResult = auditStore.list(filter, now);
    Map<String, Object> data = new LinkedHashMap<>();
    Map<String, Object> kpis = new LinkedHashMap<>();
    kpis.put("awaiting_audit", pageResult.kpis().awaitingAudit());
    kpis.put("flagged", pageResult.kpis().flagged());
    kpis.put("schedule_h1_x_count", pageResult.kpis().scheduleH1XCount());
    kpis.put("verified_today", pageResult.kpis().verifiedToday());
    kpis.put("compliance_rate_pct", pageResult.kpis().complianceRatePct());
    data.put("kpis", kpis);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (ListRow row : pageResult.items()) {
      rows.add(toListItem(row, now));
    }
    data.put("prescriptions", rows);
    return new ListResult(data, PaginationMeta.of(p, l, pageResult.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID rxId) {
    requireDetailAccess(principal);
    rateLimit("rxaudit:get:" + principal.subject(), 60, 60);
    RxAuditEntry entry = requireEntry(rxId);
    PrescriptionRecord rx = requireRx(rxId);
    Instant now = clock.instant();
    entry = refreshDuplicate(entry, rx, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rx_id", rxId);
    data.put("audit_status", entry.auditStatus());
    boolean canSeeFile =
        principal.role() == AuthRole.ADMIN_COMPLIANCE || principal.role() == AuthRole.ADMIN_SUPER;
    if (canSeeFile) {
      data.put("file_url", freshFileUrl(rx.s3Key()));
    }
    data.put("verification_checklist", buildChecklist(entry, rx));
    Map<String, Object> patient = new LinkedHashMap<>();
    patient.put("name", rx.patientName());
    patient.put("age", null);
    patient.put(
        "order_count", auditStore.dispenseContext(rxId, entry.pharmacyId()).map(d -> 1).orElse(0));
    data.put("patient", patient);

    DoctorCardPort.DoctorCard doctor =
        doctorCards
            .findForPrescription(rx.id(), rx.type(), rx.doctorName(), rx.teleconsultId())
            .orElse(
                new DoctorCardPort.DoctorCard(
                    rx.doctorName(), null, null, "E_PRESCRIPTION".equals(rx.type())));
    boolean blacklisted = false;
    String specialty = null;
    if (doctorStore != null) {
      Optional<DoctorRecord> linked =
          doctorStore.findLink(rx.id()).flatMap(l -> doctorStore.findById(l.doctorId()));
      if (linked.isPresent()) {
        blacklisted = linked.get().blacklisted();
        specialty = linked.get().specialty();
      }
    }
    Map<String, Object> doctorMap = new LinkedHashMap<>();
    doctorMap.put("name", doctor.name());
    doctorMap.put("qualification", doctor.qualification());
    doctorMap.put("registration_no", doctor.registrationNo());
    doctorMap.put("specialty", specialty);
    doctorMap.put("verified", doctor.verified());
    doctorMap.put("blacklisted", blacklisted);
    data.put("doctor", doctorMap);

    Map<String, Object> orderCtx = new LinkedHashMap<>();
    orderCtx.put("order_id", entry.orderId());
    String orderNumber =
        entry.orderId() == null
            ? null
            : auditStore
                .orderContext(entry.orderId())
                .map(RxAuditStore.OrderContext::orderNumber)
                .orElse(null);
    orderCtx.put("order_number", orderNumber);
    orderCtx.put("pharmacy_name", auditStore.pharmacyName(entry.pharmacyId()).orElse(null));
    DispenseContext dispense = auditStore.dispenseContext(rxId, entry.pharmacyId()).orElse(null);
    orderCtx.put("dispensed_at", dispense == null ? entry.createdAt() : dispense.dispensedAt());
    List<Map<String, Object>> meds =
        dispense == null ? List.of() : enrichSchedules(dispense.medicines(), entry.schedule());
    orderCtx.put("medicines_dispensed", meds);
    data.put("order_context", orderCtx);
    data.put("audit_history", auditStore.listActivity(rxId));
    data.put("possible_duplicate", entry.possibleDuplicate());
    if (entry.possibleDuplicateRxId() != null) {
      data.put("possible_duplicate_rx_id", entry.possibleDuplicateRxId());
    }
    return data;
  }

  @Transactional
  public Map<String, Object> verify(
      MedmatePrincipal principal, UUID rxId, Boolean verified, String flagReason, String notes) {
    requireMutator(principal);
    rateLimit("rxaudit:verify:" + principal.subject(), 30, 60);
    if (verified == null) {
      throw new AppException("VALIDATION_ERROR", "verified is required", 422);
    }
    if (!verified && isBlank(flagReason)) {
      throw new AppException("VALIDATION_ERROR", "flag_reason required when verified=false", 422);
    }
    if (notes != null && notes.length() > 1000) {
      throw new AppException("VALIDATION_ERROR", "notes max 1000 chars", 422);
    }
    RxAuditEntry entry = requireEntry(rxId);
    Instant now = clock.instant();
    String status = verified ? "VERIFIED" : "FLAGGED";
    RxAuditEntry updated =
        new RxAuditEntry(
            entry.id(),
            entry.rxId(),
            entry.orderId(),
            entry.pharmacyId(),
            entry.schedule(),
            status,
            entry.auditDeadline(),
            entry.possibleDuplicate(),
            entry.possibleDuplicateRxId(),
            principal.subject(),
            now,
            verified ? entry.flagReason() : flagReason,
            verified ? entry.flagSeverity() : entry.flagSeverity(),
            verified ? entry.flaggedBy() : entry.flaggedBy(),
            verified ? entry.flaggedAt() : entry.flaggedAt(),
            notes,
            entry.createdAt());
    auditStore.update(updated);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("verified", verified);
    payload.put("flag_reason", flagReason);
    payload.put("notes", notes);
    appendLog(rxId, "RX_VERIFIED", principal, payload, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rx_id", rxId);
    data.put("audit_status", status);
    data.put("verified_by", principal.subject());
    data.put("verified_at", now);
    data.put("notes", notes);
    return data;
  }

  @Transactional
  public Map<String, Object> flag(
      MedmatePrincipal principal, UUID rxId, String reason, String severity) {
    requireMutator(principal);
    rateLimit("rxaudit:flag:" + principal.subject(), 20, 60);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 422);
    }
    if (reason.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason max 500 chars", 422);
    }
    String sev = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
    if (!SEVERITIES.contains(sev)) {
      throw new AppException("VALIDATION_ERROR", "severity must be LOW|MEDIUM|HIGH", 422);
    }
    RxAuditEntry entry = requireEntry(rxId);
    Instant now = clock.instant();
    RxAuditEntry updated =
        new RxAuditEntry(
            entry.id(),
            entry.rxId(),
            entry.orderId(),
            entry.pharmacyId(),
            entry.schedule(),
            "FLAGGED",
            entry.auditDeadline(),
            entry.possibleDuplicate(),
            entry.possibleDuplicateRxId(),
            entry.verifiedBy(),
            entry.verifiedAt(),
            reason,
            sev,
            principal.subject(),
            now,
            entry.notes(),
            entry.createdAt());
    auditStore.update(updated);
    boolean escalate = "MEDIUM".equals(sev) || "HIGH".equals(sev);
    if (escalate) {
      notifications.notifyHeadOfComplianceFlag(rxId, sev, reason);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason);
    payload.put("severity", sev);
    payload.put("escalation_sent", escalate);
    appendLog(rxId, "RX_FLAGGED", principal, payload, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rx_id", rxId);
    data.put("audit_status", "FLAGGED");
    data.put("severity", sev);
    data.put("flagged_by", principal.subject());
    data.put("flagged_at", now);
    data.put("escalation_sent", escalate);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> statistics(
      MedmatePrincipal principal, String fromDate, String toDate) {
    requireMutator(principal);
    rateLimit("rxaudit:stats:" + principal.subject(), 30, 60);
    LocalDate to = parseOptionalDate(toDate, "to_date");
    if (to == null) {
      to = LocalDate.now(IST);
    }
    LocalDate from = parseOptionalDate(fromDate, "from_date");
    if (from == null) {
      from = to.minusDays(30);
    }
    if (from.isAfter(to)) {
      throw new AppException("INVALID_DATE_RANGE", "from_date is after to_date", 422);
    }
    Stats stats = auditStore.statistics(from, to);
    Map<String, Object> data = new LinkedHashMap<>();
    Map<String, Object> period = new LinkedHashMap<>();
    period.put("from", from.toString());
    period.put("to", to.toString());
    data.put("period", period);
    data.put("compliance_rate_by_schedule", stats.complianceRateBySchedule());
    data.put("flagged_rate_pct", stats.flaggedRatePct());
    data.put("top_flagged_pharmacies", stats.topFlaggedPharmacies());
    data.put("top_flagged_drugs", stats.topFlaggedDrugs());
    data.put("total_audited", stats.totalAudited());
    data.put("total_verified", stats.totalVerified());
    data.put("total_flagged", stats.totalFlagged());
    data.put("overdue_audits", stats.overdueAudits());
    return data;
  }

  @Transactional
  public int markOverdueAudits() {
    Instant now = clock.instant();
    List<RxAuditEntry> overdue = auditStore.findAwaitingPastDeadline(now, 100);
    int n = 0;
    for (RxAuditEntry e : overdue) {
      if (auditStore.markOverdue(e.id(), now) > 0) {
        notifications.notifyComplianceOverdueAudit(e.rxId(), e.pharmacyId());
        n++;
      }
    }
    return n;
  }

  private Map<String, Object> exportCsv(
      MedmatePrincipal principal,
      String schedule,
      String status,
      String source,
      String fromDate,
      String toDate,
      String search,
      UUID pharmacyId) {
    requireListAccess(principal);
    ListFilter filter =
        new ListFilter(
            normalizeSchedule(schedule),
            normalizeStatus(status == null ? "ALL" : status),
            normalizeSource(source),
            parseOptionalDate(fromDate, "from_date"),
            parseOptionalDate(toDate, "to_date"),
            blankToNull(search),
            pharmacyId,
            1,
            10_000);
    List<ListRow> rows = auditStore.listAllForExport(filter);
    Instant generatedAt = clock.instant();
    String key = StorageObjectKeys.export("rx_compliance_" + Ids.newId() + ".csv");
    byte[] csv = buildCsv(rows).getBytes(StandardCharsets.UTF_8);
    exportStore.put(key, csv, "text/csv");
    String url = exportStore.createDownloadUrl(key, DOWNLOAD_TTL);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("download_url", url);
    data.put("expires_at", generatedAt.plus(DOWNLOAD_TTL).toString());
    data.put("record_count", rows.size());
    data.put("generated_at", generatedAt.toString());
    return data;
  }

  static String buildCsv(List<ListRow> rows) {
    StringBuilder sb = new StringBuilder();
    sb.append("rx_id,patient_name,drug_name,schedule,pharmacy,dispense_date,audit_outcome\n");
    for (ListRow row : rows) {
      RxAuditEntry e = row.entry();
      sb.append(csv(e.rxId().toString())).append(',');
      sb.append(csv(row.patientName())).append(',');
      sb.append(csv(row.drugSummary())).append(',');
      sb.append(csv(e.schedule())).append(',');
      sb.append(csv(row.pharmacyName())).append(',');
      Instant dispensed = row.dispensedAt() == null ? e.createdAt() : row.dispensedAt();
      sb.append(csv(dispensed == null ? "" : dispensed.toString())).append(',');
      sb.append(csv(e.auditStatus())).append('\n');
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

  private String resolveHighestSchedule(List<ApprovedMedicine> medicines, PrescriptionRecord rx) {
    String highest = "NONE";
    boolean anyScheduleFlag = false;
    if (medicines != null) {
      for (ApprovedMedicine m : medicines) {
        String s = normalizeKnownSchedule(m.schedule());
        if (s == null && m.schedule() != null && !m.schedule().isBlank()) {
          anyScheduleFlag = true; // unknown schedule token present → H1 fallback
        }
        if (s == null) {
          s = catalogueSchedule.resolveSchedule(m.name()).orElse(null);
        }
        if (s == null) {
          s = ocrSchedule(rx, m.name());
        }
        if (s != null) {
          highest = RxAuditEntry.higher(highest, s);
        }
      }
    }
    if ("NONE".equals(highest) && anyScheduleFlag) {
      return "H1";
    }
    if ("NONE".equals(highest) && rx != null && rx.medicinesExtracted() != null) {
      for (MedicineExtracted m : rx.medicinesExtracted()) {
        String s = normalizeKnownSchedule(m.schedule());
        if (s == null) {
          s = catalogueSchedule.resolveSchedule(m.name()).orElse(null);
        }
        if (s != null) {
          highest = RxAuditEntry.higher(highest, s);
        }
      }
    }
    return highest;
  }

  private String ocrSchedule(PrescriptionRecord rx, String medicineName) {
    if (rx == null) {
      return null;
    }
    if (rx.medicinesExtracted() == null) {
      return null;
    }
    if (medicineName == null) {
      return null;
    }
    for (MedicineExtracted m : rx.medicinesExtracted()) {
      if (m.name() != null && m.name().equalsIgnoreCase(medicineName)) {
        return normalizeKnownSchedule(m.schedule());
      }
    }
    return null;
  }

  private static String normalizeKnownSchedule(String raw) {
    if (isBlank(raw)) {
      return null;
    }
    String s = raw.trim().toUpperCase(Locale.ROOT);
    if ("H".equals(s)) {
      return s;
    }
    if ("H1".equals(s)) {
      return s;
    }
    if ("X".equals(s)) {
      return s;
    }
    if (s.contains("H1")) {
      return "H1";
    }
    if (s.equals("SCHEDULE X") || s.endsWith(" X") || s.contains("SCH-X")) {
      return "X";
    }
    if (s.equals("SCHEDULE H") || s.endsWith(" H")) {
      return "H";
    }
    return null;
  }

  private DuplicateMatch detectDuplicate(
      PrescriptionRecord rx, List<ApprovedMedicine> medicines, Instant now, UUID excludeRxId) {
    if (rx == null) {
      return null;
    }
    if (rx.patientName() == null) {
      return null;
    }
    if (medicines == null) {
      return null;
    }
    if (medicines.isEmpty()) {
      return null;
    }
    Instant since = now.minus(DUPLICATE_WINDOW);
    for (ApprovedMedicine m : medicines) {
      if (isBlank(m.name())) {
        continue;
      }
      Optional<DuplicateMatch> hit =
          auditStore.findDuplicate(rx.patientName(), m.name(), m.quantity(), since, excludeRxId);
      if (hit.isPresent()) {
        return hit.get();
      }
    }
    return null;
  }

  private RxAuditEntry refreshDuplicate(RxAuditEntry entry, PrescriptionRecord rx, Instant now) {
    if (entry.possibleDuplicate()) {
      return entry;
    }
    DispenseContext ctx = auditStore.dispenseContext(entry.rxId(), entry.pharmacyId()).orElse(null);
    if (ctx == null || ctx.medicines().isEmpty()) {
      return entry;
    }
    List<ApprovedMedicine> meds = new ArrayList<>();
    for (Map<String, Object> m : ctx.medicines()) {
      String name = m.get("name") == null ? null : m.get("name").toString();
      int qty = m.get("quantity") instanceof Number n ? n.intValue() : 1;
      meds.add(new ApprovedMedicine(name, qty, null));
    }
    DuplicateMatch dup = detectDuplicate(rx, meds, now, entry.rxId());
    if (dup == null) {
      return entry;
    }
    RxAuditEntry updated =
        new RxAuditEntry(
            entry.id(),
            entry.rxId(),
            entry.orderId(),
            entry.pharmacyId(),
            entry.schedule(),
            entry.auditStatus(),
            entry.auditDeadline(),
            true,
            dup.rxId(),
            entry.verifiedBy(),
            entry.verifiedAt(),
            entry.flagReason(),
            entry.flagSeverity(),
            entry.flaggedBy(),
            entry.flaggedAt(),
            entry.notes(),
            entry.createdAt());
    auditStore.update(updated);
    return updated;
  }

  private Map<String, Object> buildChecklist(RxAuditEntry entry, PrescriptionRecord rx) {
    boolean ocr = rx.medicinesExtracted() != null && !rx.medicinesExtracted().isEmpty();
    String label = ocr ? "OCR extracted" : "manually entered";
    DoctorCardPort.DoctorCard doctor =
        doctorCards
            .findForPrescription(rx.id(), rx.type(), rx.doctorName(), rx.teleconsultId())
            .orElse(new DoctorCardPort.DoctorCard(rx.doctorName(), null, null, false));
    Map<String, Object> checklist = new LinkedHashMap<>();
    Map<String, Object> doctorReg = new LinkedHashMap<>();
    doctorReg.put("status", doctor.verified() ? "VERIFIED" : "PENDING");
    doctorReg.put("method", doctor.verified() ? "NMC_REGISTRY" : null);
    doctorReg.put("checked_at", doctor.verified() ? entry.createdAt() : null);
    doctorReg.put("source_label", label);
    checklist.put("doctor_registered", doctorReg);

    Map<String, Object> qty = new LinkedHashMap<>();
    qty.put("status", "PENDING");
    qty.put("note", null);
    qty.put("source_label", label);
    checklist.put("quantity_appropriate", qty);

    Map<String, Object> dup = new LinkedHashMap<>();
    dup.put("status", entry.possibleDuplicate() ? "FLAGGED" : "CLEAR");
    dup.put("duplicate_found", entry.possibleDuplicate());
    if (entry.possibleDuplicateRxId() != null) {
      dup.put("duplicate_rx_id", entry.possibleDuplicateRxId());
    }
    dup.put("source_label", "system");
    checklist.put("not_duplicate_rx", dup);

    Map<String, Object> schedule = new LinkedHashMap<>();
    schedule.put("schedule", entry.schedule());
    schedule.put("status", "PENDING");
    schedule.put("note", null);
    schedule.put("source_label", label);
    if (ocr) {
      List<Map<String, Object>> extracted = new ArrayList<>();
      for (MedicineExtracted m : rx.medicinesExtracted()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", m.name());
        row.put("quantity", m.quantity());
        row.put("dosage", m.dosage());
        row.put("schedule", m.schedule());
        row.put("source_label", "OCR extracted");
        extracted.add(row);
      }
      schedule.put("medicines_extracted", extracted);
    }
    checklist.put("schedule_check", schedule);
    return checklist;
  }

  private Map<String, Object> toListItem(ListRow row, Instant now) {
    RxAuditEntry e = row.entry();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rx_id", e.rxId());
    m.put("audit_status", e.auditStatus());
    m.put("is_overdue", e.isOverdue(now) || "OVERDUE_AUDIT".equals(e.auditStatus()));
    m.put("hours_since_dispense", e.hoursSinceDispense(now));
    m.put("schedule", e.schedule());
    m.put("patient_name", row.patientName());
    m.put("doctor_name", row.doctorName());
    m.put("doctor_verified", row.doctorVerified());
    m.put("pharmacy_name", row.pharmacyName());
    m.put("dispensed_at", row.dispensedAt() == null ? e.createdAt() : row.dispensedAt());
    m.put("audit_deadline", e.auditDeadline());
    m.put("possible_duplicate", e.possibleDuplicate());
    m.put("source", row.source());
    return m;
  }

  private List<Map<String, Object>> enrichSchedules(
      List<Map<String, Object>> medicines, String fallbackSchedule) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> m : medicines) {
      Map<String, Object> row = new LinkedHashMap<>(m);
      Object sch = row.get("schedule");
      String schedule = sch == null ? null : sch.toString();
      if (isBlank(schedule)) {
        Object rawName = row.get("name");
        String name = rawName == null ? null : rawName.toString();
        row.put("schedule", catalogueSchedule.resolveSchedule(name).orElse(fallbackSchedule));
      }
      out.add(row);
    }
    return out;
  }

  private void appendLog(
      UUID rxId,
      String action,
      MedmatePrincipal principal,
      Map<String, Object> payload,
      Instant now) {
    auditStore.appendActivity(
        Ids.newId(),
        rxId,
        action,
        principal.subject(),
        principal.role().value(),
        toJson(payload),
        now);
  }

  static String toJson(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : payload.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(e.getKey().replace("\"", "")).append("\":");
      Object v = e.getValue();
      if (v == null) {
        sb.append("null");
      } else if (v instanceof Boolean || v instanceof Number) {
        sb.append(v);
      } else {
        sb.append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
      }
    }
    return sb.append('}').toString();
  }

  private String freshFileUrl(String s3Key) {
    String base = presigner.createGetUrl(s3Key, SIGNED_TTL).url();
    String sep = base.contains("?") ? "&" : "?";
    return base + sep + "n=" + Ids.newId();
  }

  private RxAuditEntry requireEntry(UUID rxId) {
    return auditStore
        .findByRxId(rxId)
        .orElseThrow(() -> new AppException("RX_NOT_FOUND", "Audit entry not found", 404));
  }

  private PrescriptionRecord requireRx(UUID rxId) {
    return prescriptionStore
        .findById(rxId)
        .orElseThrow(() -> new AppException("RX_NOT_FOUND", "Prescription not found", 404));
  }

  private static void requireListAccess(MedmatePrincipal principal) {
    if (!isListRole(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin compliance access required", 403);
    }
  }

  private static void requireDetailAccess(MedmatePrincipal principal) {
    if (!isListRole(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin compliance access required", 403);
    }
  }

  private static void requireMutator(MedmatePrincipal principal) {
    if (!isMutatorRole(principal.role())) {
      throw new AppException("FORBIDDEN", "admin_compliance or admin_super required", 403);
    }
  }

  private static boolean isListRole(AuthRole role) {
    return role == AuthRole.ADMIN_COMPLIANCE
        || role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS;
  }

  private static boolean isMutatorRole(AuthRole role) {
    return role == AuthRole.ADMIN_COMPLIANCE || role == AuthRole.ADMIN_SUPER;
  }

  private static String normalizeSchedule(String schedule) {
    if (schedule == null) {
      return "ALL";
    }
    if (schedule.isBlank()) {
      return "ALL";
    }
    String s = schedule.trim().toUpperCase(Locale.ROOT);
    if (!SCHEDULES.contains(s)) {
      throw new AppException("VALIDATION_ERROR", "Invalid schedule filter", 422);
    }
    return s;
  }

  private static String normalizeStatus(String status) {
    if (status == null) {
      return "AWAITING_AUDIT";
    }
    if (status.isBlank()) {
      return "AWAITING_AUDIT";
    }
    String s = status.trim().toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(s)) {
      throw new AppException("VALIDATION_ERROR", "Invalid status filter", 422);
    }
    return s;
  }

  private static String normalizeSource(String source) {
    if (source == null) {
      return null;
    }
    if (source.isBlank()) {
      return null;
    }
    String s = source.trim().toUpperCase(Locale.ROOT);
    if ("DIGITAL".equals(s)) {
      return s;
    }
    if ("UPLOADED".equals(s)) {
      return s;
    }
    throw new AppException("VALIDATION_ERROR", "Invalid source filter", 422);
  }

  private static LocalDate parseOptionalDate(String raw, String field) {
    if (raw == null) {
      return null;
    }
    if (raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception e) {
      throw new AppException("INVALID_DATE_RANGE", "Invalid " + field, 422);
    }
  }

  private static String blankToNull(String s) {
    if (isBlank(s)) {
      return null;
    }
    return s.trim();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, retry);
    }
  }

  /** Visible for tests — UTC day start helper. */
  static Instant startOfUtcDay(LocalDate date) {
    return date.atStartOfDay().toInstant(ZoneOffset.UTC);
  }
}
