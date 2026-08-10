package com.nammamedmate.prescription.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.application.port.out.DoctorAutoFlagPort;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore.Link;
import com.nammamedmate.prescription.application.port.out.DoctorStore.ListFilter;
import com.nammamedmate.prescription.application.port.out.DoctorStore.Page;
import com.nammamedmate.prescription.application.port.out.DoctorStore.ScheduleCounts;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DoctorRegistryService {

  public static final Set<String> ALLOWED_QUALIFICATIONS =
      Set.of("MBBS", "MBBS MD", "MBBS MS", "BDS", "BAMS", "BHMS", "BUMS", "MDS", "MD");
  private static final Set<String> VERIFY_METHODS = Set.of("NMC_REGISTRY", "STATE_BOARD", "MANUAL");
  private static final Set<String> READ_ROLES =
      Set.of(
          AuthRole.ADMIN_COMPLIANCE.name(),
          AuthRole.ADMIN_SUPER.name(),
          AuthRole.ADMIN_OPERATIONS.name(),
          AuthRole.ADMIN_SUPPORT.name());
  private static final Duration SCHEDULE_ALERT_WINDOW = Duration.ofDays(30);
  private static final long SCHEDULE_ALERT_THRESHOLD = 50L;

  private final DoctorStore doctors;
  private final RxAuditStore auditStore;
  private final PharmacyRxQueueStore queueStore;
  private final NotificationDispatchPort notifications;
  private final OutboxPublisher outbox;
  private final ObjectProvider<DoctorAutoFlagPort> autoFlags;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public DoctorRegistryService(
      DoctorStore doctors,
      RxAuditStore auditStore,
      PharmacyRxQueueStore queueStore,
      NotificationDispatchPort notifications,
      OutboxPublisher outbox,
      ObjectProvider<DoctorAutoFlagPort> autoFlags,
      RateLimiter rateLimiter,
      Clock clock) {
    this.doctors = doctors;
    this.auditStore = auditStore;
    this.queueStore = queueStore;
    this.notifications = notifications;
    this.outbox = outbox;
    this.autoFlags = autoFlags;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(List<Map<String, Object>> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? List.of() : List.copyOf(data);
    }
  }

  public record UpsertResult(DoctorRecord doctor, boolean unrecognizedQualification) {}

  /** OCR completion path — upsert UNVERIFIED doctor and link Rx. */
  @Transactional
  public UpsertResult upsertFromOcr(
      UUID rxId,
      String doctorName,
      String registrationNoRaw,
      String qualificationRaw,
      String specialtyRaw) {
    Instant now = clock.instant();
    String name = doctorName == null || doctorName.isBlank() ? "Unknown Doctor" : doctorName.trim();
    String registrationNo = normalizeRegistration(registrationNoRaw, Ids.newId());
    String normalizedQual = normalizeQualification(qualificationRaw);
    boolean unrecognized = false;
    if (qualificationRaw != null && !qualificationRaw.isBlank()) {
      unrecognized = normalizedQual == null;
    }
    String specialty = specialtyRaw == null || specialtyRaw.isBlank() ? null : specialtyRaw.trim();

    Optional<DoctorRecord> existing = doctors.findByRegistrationNo(registrationNo);
    DoctorRecord doctor;
    if (existing.isPresent()) {
      DoctorRecord e = existing.get();
      doctor =
          new DoctorRecord(
              e.id(),
              e.registrationNo(),
              name,
              normalizedQual != null ? normalizedQual : e.qualification(),
              specialty != null ? specialty : e.specialty(),
              e.status(),
              e.source(),
              e.prescriptionCount(),
              e.scheduledDrugCount(),
              e.verificationMethod(),
              e.verifiedBy(),
              e.verifiedAt(),
              e.verificationNotes(),
              e.blacklistReason(),
              e.blacklistedBy(),
              e.blacklistedAt(),
              e.createdAt(),
              now,
              e.deletedAt());
      doctors.update(doctor);
      doctors.incrementPrescriptionCount(doctor.id(), now);
      doctor = doctors.findById(doctor.id()).orElse(doctor);
    } else {
      doctor =
          new DoctorRecord(
              Ids.newId(),
              registrationNo,
              name,
              normalizedQual,
              specialty,
              "UNVERIFIED",
              "OCR",
              1,
              0,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              now,
              now,
              null);
      doctors.insert(doctor);
    }
    boolean pendingBlacklist = doctor.blacklisted();
    doctors.linkPrescription(rxId, doctor.id(), unrecognized, now);
    if (pendingBlacklist) {
      doctors.markPendingBlacklist(doctor.id());
    }
    return new UpsertResult(doctors.findById(doctor.id()).orElse(doctor), unrecognized);
  }

  /** Teleconsult e-Rx path — VERIFIED doctor (EPIC-009 caller). */
  @Transactional
  public DoctorRecord upsertFromTeleconsult(
      UUID rxId, String doctorName, String registrationNo, String qualification, String specialty) {
    Instant now = clock.instant();
    String reg = normalizeRegistration(registrationNo, Ids.newId());
    String qual = normalizeQualification(qualification);
    Optional<DoctorRecord> existing = doctors.findByRegistrationNo(reg);
    DoctorRecord doctor;
    if (existing.isPresent()) {
      DoctorRecord e = existing.get();
      String status = e.blacklisted() ? "BLACKLISTED" : "VERIFIED";
      doctor =
          new DoctorRecord(
              e.id(),
              e.registrationNo(),
              doctorName == null ? e.name() : doctorName.trim(),
              qual != null ? qual : e.qualification(),
              specialty != null ? specialty : e.specialty(),
              status,
              "TELECONSULT",
              e.prescriptionCount(),
              e.scheduledDrugCount(),
              "MANUAL",
              e.verifiedBy(),
              now,
              e.verificationNotes(),
              e.blacklistReason(),
              e.blacklistedBy(),
              e.blacklistedAt(),
              e.createdAt(),
              now,
              e.deletedAt());
      doctors.update(doctor);
      doctors.incrementPrescriptionCount(doctor.id(), now);
    } else {
      doctor =
          new DoctorRecord(
              Ids.newId(),
              reg,
              doctorName == null || doctorName.isBlank() ? "Teleconsult Doctor" : doctorName.trim(),
              qual,
              specialty,
              "VERIFIED",
              "TELECONSULT",
              1,
              0,
              "MANUAL",
              null,
              now,
              null,
              null,
              null,
              null,
              now,
              now,
              null);
      doctors.insert(doctor);
    }
    doctors.linkPrescription(rxId, doctor.id(), false, now);
    return doctors.findById(doctor.id()).orElse(doctor);
  }

  @Transactional
  public void recordScheduledDrug(UUID rxId) {
    Optional<Link> link = doctors.findLink(rxId);
    if (link.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    UUID doctorId = link.get().doctorId();
    doctors.incrementScheduledDrugCount(doctorId, now);
    doctors.insertScheduleEvent(Ids.newId(), doctorId, rxId, now);
    long windowCount = doctors.countScheduleEventsSince(doctorId, now.minus(SCHEDULE_ALERT_WINDOW));
    if (windowCount > SCHEDULE_ALERT_THRESHOLD) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("doctor_id", doctorId);
      payload.put("rx_id", rxId);
      payload.put("scheduled_drug_count_30d", windowCount);
      payload.put("threshold", SCHEDULE_ALERT_THRESHOLD);
      outbox.publish(
          DomainEvent.of(
              "prescription.doctor.scheduled_drug_soft_alert", "doctor", doctorId, payload));
      notifications.notifyComplianceDoctorScheduleAlert(doctorId, windowCount);
    }
  }

  @Transactional(readOnly = true)
  public ListResult list(
      MedmatePrincipal principal,
      String search,
      String specialty,
      String status,
      Integer pageRaw,
      Integer limitRaw,
      String sort,
      String order) {
    requireRead(principal);
    rateLimit("doctor:list:" + principal.subject(), 60, 60);
    int page = pageRaw == null || pageRaw < 1 ? 1 : pageRaw;
    int limit = limitRaw == null ? 20 : Math.min(Math.max(limitRaw, 1), 100);
    String statusFilter =
        status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("UNVERIFIED", "VERIFIED", "BLACKLISTED", "ALL").contains(statusFilter)) {
      throw new AppException(
          "VALIDATION_ERROR", "status must be UNVERIFIED|VERIFIED|BLACKLISTED|ALL", 400);
    }
    String sortCol =
        sort == null || sort.isBlank()
            ? "prescription_count"
            : sort.trim().toLowerCase(Locale.ROOT);
    String ord = order == null || order.isBlank() ? "desc" : order.trim().toLowerCase(Locale.ROOT);
    Page result =
        doctors.list(new ListFilter(search, specialty, statusFilter, page, limit, sortCol, ord));
    List<Map<String, Object>> data = new ArrayList<>();
    for (DoctorRecord d : result.items()) {
      data.add(toListItem(d));
    }
    return new ListResult(data, PaginationMeta.of(page, limit, result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireRead(principal);
    rateLimit("doctor:get:" + principal.subject(), 60, 60);
    DoctorRecord d = requireDoctor(id);
    ScheduleCounts schedules = doctors.scheduleCounts(d.id());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", d.id());
    data.put("registration_no", d.registrationNo());
    data.put("name", d.name());
    data.put("qualification", d.qualification());
    data.put("specialty", d.specialty());
    data.put("status", d.status());
    data.put("verification_method", d.verificationMethod());
    data.put("verified_at", d.verifiedAt());
    data.put("verified_by", d.verifiedBy());
    data.put("source", d.source());
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("total_prescriptions", d.prescriptionCount());
    stats.put("scheduled_h_count", schedules.scheduledH());
    stats.put("scheduled_h1_count", schedules.scheduledH1());
    stats.put("scheduled_x_count", schedules.scheduledX());
    stats.put("prescriptions_by_category", doctors.prescriptionCategoryCounts(d.id()));
    data.put("prescription_stats", stats);
    data.put("associated_orders_count", doctors.associatedOrdersCount(d.id()));
    data.put("blacklisted", d.blacklisted());
    data.put("blacklist_reason", d.blacklistReason());
    data.put("blacklisted_at", d.blacklistedAt());
    data.put("created_at", d.createdAt());
    data.put("updated_at", d.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> verify(
      MedmatePrincipal principal,
      UUID id,
      Boolean verified,
      String verificationMethod,
      String notes) {
    requireMutator(principal);
    rateLimit("doctor:verify:" + principal.subject(), 20, 60);
    if (verified == null) {
      throw new AppException("VALIDATION_ERROR", "verified is required", 400);
    }
    if (verificationMethod == null || verificationMethod.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "verification_method is required", 400);
    }
    String method = verificationMethod.trim().toUpperCase(Locale.ROOT);
    if (!VERIFY_METHODS.contains(method)) {
      throw new AppException(
          "VALIDATION_ERROR", "verification_method must be NMC_REGISTRY|STATE_BOARD|MANUAL", 400);
    }
    if (notes != null && notes.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 chars", 400);
    }
    DoctorRecord d = requireDoctor(id);
    if (d.blacklisted()) {
      throw new AppException(
          "DOCTOR_ALREADY_BLACKLISTED", "Blacklisted doctors cannot be verified", 409);
    }
    Instant now = clock.instant();
    DoctorRecord updated;
    if (verified) {
      updated =
          new DoctorRecord(
              d.id(),
              d.registrationNo(),
              d.name(),
              d.qualification(),
              d.specialty(),
              "VERIFIED",
              d.source(),
              d.prescriptionCount(),
              d.scheduledDrugCount(),
              method,
              principal.subject(),
              now,
              notes,
              d.blacklistReason(),
              d.blacklistedBy(),
              d.blacklistedAt(),
              d.createdAt(),
              now,
              d.deletedAt());
    } else {
      updated =
          new DoctorRecord(
              d.id(),
              d.registrationNo(),
              d.name(),
              d.qualification(),
              d.specialty(),
              "UNVERIFIED",
              d.source(),
              d.prescriptionCount(),
              d.scheduledDrugCount(),
              method,
              null,
              null,
              notes,
              d.blacklistReason(),
              d.blacklistedBy(),
              d.blacklistedAt(),
              d.createdAt(),
              now,
              d.deletedAt());
    }
    doctors.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("status", updated.status());
    data.put("verification_method", updated.verificationMethod());
    data.put("verified_by", updated.verifiedBy());
    data.put("verified_at", updated.verifiedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> blacklist(MedmatePrincipal principal, UUID id, String reason) {
    requireMutator(principal);
    rateLimit("doctor:blacklist:" + principal.subject(), 10, 60);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    if (reason.length() > 1000) {
      throw new AppException("VALIDATION_ERROR", "reason max 1000 chars", 400);
    }
    DoctorRecord d = requireDoctor(id);
    if (d.blacklisted()) {
      throw new AppException(
          "DOCTOR_ALREADY_BLACKLISTED", "Doctor already has BLACKLISTED status", 409);
    }
    Instant now = clock.instant();
    DoctorRecord updated =
        new DoctorRecord(
            d.id(),
            d.registrationNo(),
            d.name(),
            d.qualification(),
            d.specialty(),
            "BLACKLISTED",
            d.source(),
            d.prescriptionCount(),
            d.scheduledDrugCount(),
            d.verificationMethod(),
            d.verifiedBy(),
            d.verifiedAt(),
            d.verificationNotes(),
            reason.trim(),
            principal.subject(),
            now,
            d.createdAt(),
            now,
            d.deletedAt());
    doctors.update(updated);
    int queued = doctors.countRxForDoctor(d.id());
    doctors.markPendingBlacklist(d.id());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("doctor_id", d.id());
    payload.put("retroactive_flags_queued", queued);
    payload.put("blacklisted_by", principal.subject());
    outbox.publish(
        DomainEvent.of("prescription.doctor.blacklist_retroactive", "doctor", d.id(), payload));
    processRetroactiveFlags(d.id(), now);
    notifications.notifyComplianceDoctorBlacklisted(d.id(), reason.trim());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("status", "BLACKLISTED");
    data.put("blacklist_reason", updated.blacklistReason());
    data.put("blacklisted_by", updated.blacklistedBy());
    data.put("blacklisted_at", updated.blacklistedAt());
    data.put("retroactive_flags_queued", queued);
    data.put(
        "message",
        "Doctor blacklisted. "
            + queued
            + " associated prescriptions queued for retroactive flagging.");
    return data;
  }

  public record UnverifiedResult(Map<String, Object> data, PaginationMeta meta) {
    public UnverifiedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public UnverifiedResult listUnverified(
      MedmatePrincipal principal, Integer pageRaw, Integer limitRaw, String sort) {
    requireMutator(principal);
    rateLimit("doctor:unverified:" + principal.subject(), 30, 60);
    int page = pageRaw == null || pageRaw < 1 ? 1 : pageRaw;
    int limit = limitRaw == null ? 20 : Math.min(Math.max(limitRaw, 1), 100);
    // AC: always prescription_count DESC (sort param ignored intentionally)
    Page result = doctors.listUnverified(page, limit);
    List<Map<String, Object>> doctorsList = new ArrayList<>();
    for (DoctorRecord d : result.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", d.id());
      row.put("registration_no", d.registrationNo());
      row.put("name", d.name());
      row.put("qualification", d.qualification());
      row.put("specialty", d.specialty());
      row.put("prescription_count", d.prescriptionCount());
      row.put("scheduled_drug_count", d.scheduledDrugCount());
      row.put("source", d.source());
      row.put("first_seen_at", d.createdAt());
      doctorsList.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_unverified", result.total());
    data.put("doctors", doctorsList);
    return new UnverifiedResult(data, PaginationMeta.of(page, limit, result.total()));
  }

  void processRetroactiveFlags(UUID doctorId, Instant now) {
    List<UUID> rxIds = doctors.listRxIdsForDoctor(doctorId);
    for (UUID rxId : rxIds) {
      Optional<RxAuditEntry> existing = auditStore.findByRxId(rxId);
      if (existing.isPresent()) {
        RxAuditEntry e = existing.get();
        if (!"FLAGGED".equals(e.auditStatus()) || !"BLACKLISTED_DOCTOR".equals(e.flagReason())) {
          flagAudit(e, now);
        }
        continue;
      }
      Optional<PharmacyRxQueueEntry> queue = findAnyQueue(rxId);
      if (queue.isPresent()) {
        DoctorAutoFlagPort port = autoFlags.getIfAvailable();
        if (port != null) {
          port.applyPendingFlags(rxId, queue.get().pharmacyId());
        }
      }
    }
  }

  private Optional<PharmacyRxQueueEntry> findAnyQueue(UUID rxId) {
    // PharmacyRxQueueStore has findByRxAndPharmacy — list via JDBC helper not available;
    // ponytail: try common path through audit dispense context is unavailable; use store scan.
    return queueStore.findLatestByRxId(rxId);
  }

  private void flagAudit(RxAuditEntry e, Instant now) {
    RxAuditEntry updated =
        new RxAuditEntry(
            e.id(),
            e.rxId(),
            e.orderId(),
            e.pharmacyId(),
            e.schedule(),
            "FLAGGED",
            e.auditDeadline(),
            e.possibleDuplicate(),
            e.possibleDuplicateRxId(),
            e.verifiedBy(),
            e.verifiedAt(),
            "BLACKLISTED_DOCTOR",
            "HIGH",
            null,
            now,
            e.notes(),
            e.createdAt());
    auditStore.update(updated);
  }

  public static String normalizeQualification(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String q = raw.trim().replaceAll("\\s+", " ");
    for (String allowed : ALLOWED_QUALIFICATIONS) {
      if (allowed.equalsIgnoreCase(q)) {
        return allowed;
      }
    }
    return null;
  }

  public static String normalizeRegistration(String raw, UUID idForUnknown) {
    if (raw == null || raw.isBlank()) {
      String prefix =
          idForUnknown.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
      return "UNKNOWN-" + prefix;
    }
    return raw.trim();
  }

  private Map<String, Object> toListItem(DoctorRecord d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id());
    m.put("registration_no", d.registrationNo());
    m.put("name", d.name());
    m.put("qualification", d.qualification());
    m.put("specialty", d.specialty());
    m.put("prescription_count", d.prescriptionCount());
    m.put("scheduled_drug_count", d.scheduledDrugCount());
    m.put("status", d.status());
    m.put("verification_method", d.verificationMethod());
    m.put("verified_at", d.verifiedAt());
    m.put("source", d.source());
    return m;
  }

  private DoctorRecord requireDoctor(UUID id) {
    return doctors
        .findById(id)
        .orElseThrow(() -> new AppException("DOCTOR_NOT_FOUND", "Doctor ID not found", 404));
  }

  private void requireRead(MedmatePrincipal principal) {
    if (principal == null || !READ_ROLES.contains(principal.role().name())) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
  }

  private void requireMutator(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_COMPLIANCE && role != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }
}
