package com.nammamedmate.teleconsult.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminDayStats;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminListFilter;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminListItem;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.AdminPage;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.QueueItem;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.ConsultStatusEvent;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin consult session lifecycle (EPIC-009 STORY-003). */
@Service
public class ConsultSessionService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Set<String> ADMIN_ROLES =
      Set.of(AuthRole.ADMIN_SUPER.name(), AuthRole.ADMIN_OPERATIONS.name());
  private static final Set<String> STATUSES =
      Set.of(
          "ALL",
          Consult.STATUS_REQUESTED,
          Consult.STATUS_DOCTOR_REVIEWING,
          Consult.STATUS_CALLING,
          Consult.STATUS_IN_CALL,
          Consult.STATUS_COMPLETED,
          Consult.STATUS_CANCELLED);

  private final ConsultStore consultStore;
  private final TeleconsultDoctorStore doctorStore;
  private final NotificationDispatchPort notifications;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public ConsultSessionService(
      ConsultStore consultStore,
      TeleconsultDoctorStore doctorStore,
      NotificationDispatchPort notifications,
      RateLimiter rateLimiter,
      Clock clock) {
    this.consultStore = consultStore;
    this.doctorStore = doctorStore;
    this.notifications = notifications;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record AdminListResult(Map<String, Object> data, PaginationMeta meta) {
    public AdminListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional
  public Map<String, Object> updateStatus(
      MedmatePrincipal principal,
      UUID consultId,
      String statusRaw,
      String notes,
      Boolean adviceOnly,
      String clinicalNotes) {
    UUID actorId = requireAdmin(principal);
    rateLimit("teleconsult:admin:status:" + actorId, 30, 60);

    String target = requireStatus(statusRaw);
    String note = normalizeNotes(notes);
    Consult existing =
        consultStore
            .findById(consultId)
            .orElseThrow(() -> new AppException("CONSULT_NOT_FOUND", "Consult ID not found", 404));

    if (!Consult.canTransition(existing.status(), target)) {
      throw new AppException(
          "INVALID_STATUS_TRANSITION",
          "Cannot transition from " + existing.status() + " to " + target,
          422);
    }

    Instant now = clock.instant();
    Instant callStarted = existing.callStartedAt();
    Instant callEnded = existing.callEndedAt();
    BigDecimal duration = existing.durationMinutes();
    boolean advice = existing.adviceOnly();
    String clinical = existing.clinicalNotes();

    if (clinicalNotes != null && !clinicalNotes.isBlank()) {
      clinical = clinicalNotes.trim();
    }
    if (Boolean.TRUE.equals(adviceOnly)) {
      advice = true;
    }

    if (Consult.STATUS_IN_CALL.equals(target)) {
      callStarted = now;
    }

    if (Consult.STATUS_COMPLETED.equals(target)) {
      callEnded = now;
      if (callStarted == null) {
        duration = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        advice = true;
      } else {
        long seconds = Math.max(0, Duration.between(callStarted, callEnded).getSeconds());
        duration =
            BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
      }
      if (existing.ePrescriptionId() == null && !advice) {
        throw new AppException(
            "EPRESCRIPTION_REQUIRED", "Cannot complete without e-prescription or advice_only", 422);
      }
    }

    Consult updated =
        new Consult(
            existing.id(),
            existing.customerId(),
            existing.doctorId(),
            existing.patientName(),
            existing.patientPhone(),
            existing.slotType(),
            existing.scheduledAt(),
            existing.symptoms(),
            existing.medicinesNeedingRx(),
            existing.cartId(),
            existing.cartMode(),
            existing.reason(),
            target,
            callStarted,
            callEnded,
            duration,
            existing.ePrescriptionId(),
            advice,
            clinical,
            existing.rating(),
            existing.feedbackText(),
            existing.ratedAt(),
            existing.autoCancelledReason(),
            existing.createdAt(),
            now,
            existing.deletedAt());
    consultStore.update(updated);
    consultStore.insertStatusEvent(
        new ConsultStatusEvent(
            Ids.newId(), updated.id(), existing.status(), target, actorId, note, now));

    if (Consult.STATUS_COMPLETED.equals(target) && updated.doctorId() != null) {
      bumpDoctorOnCompletion(updated.doctorId(), callEnded, now);
    }

    notifications.notifyConsultStatusUpdated(updated.customerId(), updated.id(), target);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("consult_id", updated.id());
    data.put("status", updated.status());
    data.put("previous_status", existing.status());
    data.put("call_started_at", updated.callStartedAt());
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  public Map<String, Object> queue(MedmatePrincipal principal) {
    UUID actorId = requireAdmin(principal);
    rateLimit("teleconsult:admin:queue:" + actorId, 30, 60);
    Instant now = clock.instant();
    Map<String, Long> counts = consultStore.countActiveByStatus();
    long totalActive = counts.values().stream().mapToLong(Long::longValue).sum();
    Map<String, Object> statusCounts = new LinkedHashMap<>();
    statusCounts.put("REQUESTED", counts.getOrDefault(Consult.STATUS_REQUESTED, 0L));
    statusCounts.put("DOCTOR_REVIEWING", counts.getOrDefault(Consult.STATUS_DOCTOR_REVIEWING, 0L));
    statusCounts.put("CALLING", counts.getOrDefault(Consult.STATUS_CALLING, 0L));
    statusCounts.put("IN_CALL", counts.getOrDefault(Consult.STATUS_IN_CALL, 0L));
    statusCounts.put("total_active", totalActive);

    List<Map<String, Object>> pending =
        consultStore.listActiveQueue().stream().map(item -> toQueueItem(item, now)).toList();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("status_counts", statusCounts);
    data.put("pending_list", pending);
    return data;
  }

  public AdminListResult list(
      MedmatePrincipal principal,
      String dateRaw,
      UUID doctorId,
      String statusRaw,
      Boolean isCartMode,
      Integer page,
      Integer limit) {
    UUID actorId = requireAdmin(principal);
    rateLimit("teleconsult:admin:list:" + actorId, 30, 60);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    LocalDate date = parseDate(dateRaw);
    Instant rangeStart = date.atStartOfDay(IST).toInstant();
    Instant rangeEnd = date.plusDays(1).atStartOfDay(IST).toInstant();
    String status = normalizeListStatus(statusRaw);

    AdminDayStats dayStats = consultStore.adminDayStats(rangeStart, rangeEnd);
    AdminPage result =
        consultStore.adminList(
            new AdminListFilter(rangeStart, rangeEnd, doctorId, status, isCartMode, p, lim));

    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("total_today", dayStats.totalToday());
    stats.put("completed", dayStats.completed());
    stats.put("in_progress", dayStats.inProgress());
    stats.put("cancelled", dayStats.cancelled());
    stats.put(
        "avg_duration_minutes",
        dayStats.avgDurationMinutes() == null ? 0 : dayStats.avgDurationMinutes());
    stats.put("avg_rating", dayStats.avgRating() == null ? 0 : dayStats.avgRating());
    stats.put("pending_rating", dayStats.pendingRating());

    List<Map<String, Object>> consults =
        result.items().stream().map(this::toAdminListItem).toList();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("stats", stats);
    data.put("consults", consults);
    return new AdminListResult(data, PaginationMeta.of(p, lim, result.total()));
  }

  private void bumpDoctorOnCompletion(UUID doctorId, Instant callEndedAt, Instant now) {
    TeleconsultDoctor doctor = doctorStore.findById(doctorId).orElse(null);
    if (doctor == null) {
      return;
    }
    TeleconsultDoctor updated =
        new TeleconsultDoctor(
            doctor.id(),
            doctor.name(),
            doctor.qualification(),
            doctor.registrationNo(),
            doctor.specialty(),
            doctor.languagesSpoken(),
            doctor.yearsExperience(),
            doctor.avatarUrl(),
            doctor.bio(),
            doctor.internalPhoneCiphertext(),
            doctor.available(),
            doctor.avgRating(),
            doctor.totalConsults() + 1,
            doctor.consultsToday() + 1,
            callEndedAt,
            doctor.createdAt(),
            now,
            doctor.deletedAt());
    doctorStore.update(updated);
  }

  private Map<String, Object> toQueueItem(QueueItem item, Instant now) {
    Instant waitFrom = item.callStartedAt() != null ? item.callStartedAt() : item.createdAt();
    long waitMinutes = Math.max(0, Duration.between(waitFrom, now).toMinutes());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("consult_id", item.consultId());
    m.put("status", item.status());
    m.put("patient_name", item.patientName());
    m.put("patient_phone", item.patientPhone());
    m.put("doctor_name", item.doctorName());
    m.put("medicines_requested", item.medicinesRequested());
    m.put("call_started_at", item.callStartedAt());
    m.put("wait_time_minutes", waitMinutes);
    m.put("is_cart_mode", item.cartMode());
    return m;
  }

  private Map<String, Object> toAdminListItem(AdminListItem item) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("consult_id", item.consultId());
    m.put("patient_name", item.patientName());
    m.put("doctor_name", item.doctorName());
    m.put("status", item.status());
    m.put("duration_minutes", item.durationMinutes());
    m.put("e_prescription_issued", item.ePrescriptionIssued());
    m.put("is_cart_mode", item.cartMode());
    m.put("rating", item.rating());
    m.put("created_at", item.createdAt());
    m.put("completed_at", item.completedAt());
    return m;
  }

  private static String requireStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "status is required", 400);
    }
    String s = raw.trim().toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(s) || "ALL".equals(s)) {
      throw new AppException("VALIDATION_ERROR", "invalid status", 400);
    }
    return s;
  }

  private static String normalizeListStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ALL";
    }
    String s = status.trim().toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(s)) {
      throw new AppException("VALIDATION_ERROR", "invalid status filter", 400);
    }
    return s;
  }

  private LocalDate parseDate(String dateRaw) {
    if (dateRaw == null || dateRaw.isBlank()) {
      return LocalDate.now(clock.withZone(IST));
    }
    try {
      return LocalDate.parse(dateRaw.trim());
    } catch (DateTimeParseException ex) {
      throw new AppException("VALIDATION_ERROR", "date must be YYYY-MM-DD", 400);
    }
  }

  private static String normalizeNotes(String notes) {
    if (notes == null || notes.isBlank()) {
      return null;
    }
    String n = notes.trim();
    if (n.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 characters", 400);
    }
    return n;
  }

  private UUID requireAdmin(MedmatePrincipal principal) {
    if (principal == null || !ADMIN_ROLES.contains(principal.role().name())) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
    return principal.subject();
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }
}
