package com.nammamedmate.teleconsult.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.teleconsult.application.port.out.CartPort;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.ListFilter;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.ListItem;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.Page;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.Consult.MedicineNeed;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
public class ConsultService {

  static final int MAX_ACTIVE = 3;
  static final int DEFAULT_AVG_CALL_MINUTES = 7;
  static final int ESTIMATED_CALL_IN_MINUTES_ASSIGNED = 3;
  static final String AUTO_CANCEL_REASON = "SCHEDULED_SLOT_EXPIRED";

  private static final Set<String> MED_REASONS = Set.of("REFILL", "NEW_SYMPTOMS", "DOCTOR_ADVISED");
  private static final Set<String> CONSULT_REASONS = Set.of("GENERAL", "RX_NEEDED");
  private static final Set<String> LIST_STATUSES =
      Set.of(
          "ALL", "REQUESTED", "DOCTOR_REVIEWING", "CALLING", "IN_CALL", "COMPLETED", "CANCELLED");

  private final ConsultStore consultStore;
  private final TeleconsultDoctorStore doctorStore;
  private final CartPort cartPort;
  private final NotificationDispatchPort notifications;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public ConsultService(
      ConsultStore consultStore,
      TeleconsultDoctorStore doctorStore,
      CartPort cartPort,
      NotificationDispatchPort notifications,
      RateLimiter rateLimiter,
      Clock clock) {
    this.consultStore = consultStore;
    this.doctorStore = doctorStore;
    this.cartPort = cartPort;
    this.notifications = notifications;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(List<Map<String, Object>> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? List.of() : List.copyOf(data);
    }
  }

  @Transactional
  public Map<String, Object> request(
      MedmatePrincipal principal,
      String patientName,
      String patientPhone,
      String slot,
      List<String> symptoms,
      List<Map<String, Object>> medicinesNeedingRx,
      UUID cartId,
      String reason) {
    UUID customerId = requireCustomer(principal);
    rateLimit("teleconsult:consult:request:" + customerId, 5, 60);

    String name = requireNonBlank(patientName, "patient_name");
    String phone = requireNonBlank(patientPhone, "patient_phone");
    String consultReason = requireConsultReason(reason);
    List<String> symptomList = requireSymptoms(symptoms);
    List<MedicineNeed> meds = requireMedicines(medicinesNeedingRx);
    SlotParsed slotParsed = parseSlot(slot);

    if (consultStore.countActiveByCustomer(customerId) >= MAX_ACTIVE) {
      throw new AppException(
          "MAX_ACTIVE_CONSULTS_REACHED", "Customer already has 3 active consults", 429);
    }

    boolean cartMode = cartId != null;
    if (cartMode) {
      if (!cartPort.isActiveCartOwnedBy(cartId, customerId)) {
        throw new AppException("CART_NOT_FOUND", "Cart not found or not active", 404);
      }
      if (consultStore.hasActiveCartModeConsult(cartId)) {
        throw new AppException(
            "CART_ALREADY_HAS_CONSULT", "Cart already linked to another active consult", 409);
      }
    }

    Instant now = clock.instant();
    UUID consultId = Ids.newId();
    UUID doctorId = null;
    String status = Consult.STATUS_REQUESTED;
    TeleconsultDoctor assigned = null;

    if (Consult.SLOT_NOW.equals(slotParsed.slotType())) {
      Optional<TeleconsultDoctor> pick =
          TeleconsultDoctorService.selectLeastRecentlyAssigned(doctorStore.listAvailable());
      if (pick.isPresent()) {
        assigned = pick.get();
        doctorId = assigned.id();
        status = Consult.STATUS_DOCTOR_REVIEWING;
        Instant assignedAt = now;
        TeleconsultDoctor updated =
            new TeleconsultDoctor(
                assigned.id(),
                assigned.name(),
                assigned.qualification(),
                assigned.registrationNo(),
                assigned.specialty(),
                assigned.languagesSpoken(),
                assigned.yearsExperience(),
                assigned.avatarUrl(),
                assigned.bio(),
                assigned.internalPhoneCiphertext(),
                assigned.available(),
                assigned.avgRating(),
                assigned.totalConsults(),
                assigned.consultsToday(),
                assignedAt,
                assigned.createdAt(),
                assignedAt,
                assigned.deletedAt());
        doctorStore.update(updated);
        assigned = updated;
      }
    }

    Consult consult =
        new Consult(
            consultId,
            customerId,
            doctorId,
            name,
            phone,
            slotParsed.slotType(),
            slotParsed.scheduledAt(),
            symptomList,
            meds,
            cartId,
            cartMode,
            consultReason,
            status,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    consultStore.insert(consult);
    return toRequestResponse(consult, assigned);
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID consultId) {
    UUID customerId = requireCustomer(principal);
    rateLimit("teleconsult:consult:get:" + customerId, 60, 60);
    Consult consult = requireOwnConsult(consultId, customerId);
    TeleconsultDoctor doctor =
        consult.doctorId() == null ? null : doctorStore.findById(consult.doctorId()).orElse(null);
    return toDetailResponse(consult, doctor);
  }

  @Transactional
  public Map<String, Object> cancel(MedmatePrincipal principal, UUID consultId, String reason) {
    UUID customerId = requireCustomer(principal);
    rateLimit("teleconsult:consult:cancel:" + customerId, 10, 60);
    Consult existing = requireOwnConsult(consultId, customerId);
    if (!existing.customerCancellable()) {
      throw new AppException(
          "CONSULT_CANNOT_CANCEL",
          "Consult cannot be cancelled in status " + existing.status(),
          409);
    }
    Instant now = clock.instant();
    Consult cancelled =
        copyWithStatus(
            existing,
            Consult.STATUS_CANCELLED,
            reason == null || reason.isBlank() ? existing.autoCancelledReason() : reason.trim(),
            now);
    consultStore.update(cancelled);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("consult_id", cancelled.id());
    data.put("status", cancelled.status());
    data.put("cancelled_at", cancelled.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> rate(
      MedmatePrincipal principal, UUID consultId, Integer rating, String feedbackText) {
    UUID customerId = requireCustomer(principal);
    rateLimit("teleconsult:consult:rate:" + customerId, 5, 60);
    Consult existing = requireOwnConsult(consultId, customerId);
    if (!Consult.STATUS_COMPLETED.equals(existing.status())) {
      throw new AppException("CONSULT_NOT_COMPLETED", "Consult is not in COMPLETED status", 409);
    }
    if (existing.rating() != null || existing.ratedAt() != null) {
      throw new AppException("ALREADY_RATED", "Customer has already rated this consult", 409);
    }
    if (rating == null || rating < 1 || rating > 5) {
      throw new AppException("INVALID_RATING", "Rating is not in range 1-5", 422);
    }
    String feedback = normalizeFeedback(feedbackText);
    Instant now = clock.instant();
    int previousRatedCount =
        existing.doctorId() == null
            ? 0
            : (int) consultStore.countRatingsByDoctor(existing.doctorId());
    Consult ratedConsult =
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
            existing.status(),
            existing.callStartedAt(),
            existing.callEndedAt(),
            existing.durationMinutes(),
            existing.ePrescriptionId(),
            existing.adviceOnly(),
            existing.clinicalNotes(),
            rating,
            feedback,
            now,
            existing.autoCancelledReason(),
            existing.createdAt(),
            now,
            existing.deletedAt());
    consultStore.update(ratedConsult);

    if (ratedConsult.doctorId() != null) {
      doctorStore
          .findById(ratedConsult.doctorId())
          .ifPresent(
              doctor -> {
                int previousCount = previousRatedCount;
                if (previousCount == 0 && doctor.avgRating() != null) {
                  previousCount = Math.max(doctor.totalConsults(), 0);
                }
                BigDecimal nextAvg =
                    TeleconsultDoctorService.runningAverageRating(
                        doctor.avgRating(), previousCount, rating);
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
                        nextAvg,
                        doctor.totalConsults(),
                        doctor.consultsToday(),
                        doctor.lastAssignedAt(),
                        doctor.createdAt(),
                        now,
                        doctor.deletedAt());
                doctorStore.update(updated);
              });
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("consult_id", ratedConsult.id());
    data.put("rating", ratedConsult.rating());
    data.put("feedback_text", ratedConsult.feedbackText());
    data.put("rated_at", ratedConsult.ratedAt());
    return data;
  }

  public ListResult list(MedmatePrincipal principal, String status, Integer page, Integer limit) {
    UUID customerId = requireCustomer(principal);
    rateLimit("teleconsult:consult:list:" + customerId, 30, 60);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    String statusFilter = normalizeListStatus(status);
    Page result = consultStore.list(new ListFilter(customerId, statusFilter, p, lim));
    List<Map<String, Object>> data = result.items().stream().map(this::toListItem).toList();
    return new ListResult(data, PaginationMeta.of(p, lim, result.total()));
  }

  /** Auto-cancel scheduled consults past scheduled_at + 30 minutes still not started. */
  @Transactional
  public int autoCancelOverdue() {
    Instant now = clock.instant();
    List<Consult> due = consultStore.findDueForAutoCancel(now);
    int n = 0;
    for (Consult existing : due) {
      Consult cancelled =
          copyWithStatus(existing, Consult.STATUS_CANCELLED, AUTO_CANCEL_REASON, now);
      consultStore.update(cancelled);
      notifications.notifyConsultAutoCancelled(cancelled.customerId(), cancelled.id());
      n++;
    }
    return n;
  }

  /** Assign LRU doctors to scheduled consults whose slot time has arrived (D13). */
  @Transactional
  public int assignDueScheduled() {
    Instant now = clock.instant();
    int n = 0;
    n += assignUnassigned(consultStore.findDueForScheduledAssign(now), now);
    n += assignUnassigned(consultStore.findQueuedNowUnassigned(), now);
    return n;
  }

  private int assignUnassigned(List<Consult> queue, Instant now) {
    int n = 0;
    for (Consult existing : queue) {
      Optional<TeleconsultDoctor> pick =
          TeleconsultDoctorService.selectLeastRecentlyAssigned(doctorStore.listAvailable());
      if (pick.isEmpty()) {
        break;
      }
      TeleconsultDoctor assigned = pick.get();
      Instant assignedAt = now;
      TeleconsultDoctor updated =
          new TeleconsultDoctor(
              assigned.id(),
              assigned.name(),
              assigned.qualification(),
              assigned.registrationNo(),
              assigned.specialty(),
              assigned.languagesSpoken(),
              assigned.yearsExperience(),
              assigned.avatarUrl(),
              assigned.bio(),
              assigned.internalPhoneCiphertext(),
              assigned.available(),
              assigned.avgRating(),
              assigned.totalConsults(),
              assigned.consultsToday(),
              assignedAt,
              assigned.createdAt(),
              assignedAt,
              assigned.deletedAt());
      doctorStore.update(updated);
      consultStore.update(
          copyWithDoctor(existing, assigned.id(), Consult.STATUS_DOCTOR_REVIEWING, now));
      n++;
    }
    return n;
  }

  private static Consult copyWithDoctor(
      Consult existing, UUID doctorId, String status, Instant now) {
    return new Consult(
        existing.id(),
        existing.customerId(),
        doctorId,
        existing.patientName(),
        existing.patientPhone(),
        existing.slotType(),
        existing.scheduledAt(),
        existing.symptoms(),
        existing.medicinesNeedingRx(),
        existing.cartId(),
        existing.cartMode(),
        existing.reason(),
        status,
        existing.callStartedAt(),
        existing.callEndedAt(),
        existing.durationMinutes(),
        existing.ePrescriptionId(),
        existing.adviceOnly(),
        existing.clinicalNotes(),
        existing.rating(),
        existing.feedbackText(),
        existing.ratedAt(),
        existing.autoCancelledReason(),
        existing.createdAt(),
        now,
        existing.deletedAt());
  }

  private static Consult copyWithStatus(
      Consult existing, String status, String autoCancelledReason, Instant now) {
    return new Consult(
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
        status,
        existing.callStartedAt(),
        existing.callEndedAt(),
        existing.durationMinutes(),
        existing.ePrescriptionId(),
        existing.adviceOnly(),
        existing.clinicalNotes(),
        existing.rating(),
        existing.feedbackText(),
        existing.ratedAt(),
        autoCancelledReason,
        existing.createdAt(),
        now,
        existing.deletedAt());
  }

  private static String normalizeFeedback(String feedbackText) {
    if (feedbackText == null || feedbackText.isBlank()) {
      return null;
    }
    String f = feedbackText.trim();
    if (f.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "feedback_text max 500 characters", 400);
    }
    return f;
  }

  private Map<String, Object> toRequestResponse(Consult consult, TeleconsultDoctor assigned) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("consult_id", consult.id());
    data.put("status", consult.status());
    if (assigned != null) {
      data.put("doctor", doctorCard(assigned));
      data.put("estimated_call_in_minutes", ESTIMATED_CALL_IN_MINUTES_ASSIGNED);
    } else if (Consult.SLOT_NOW.equals(consult.slotType())) {
      data.put("doctor", null);
      int position = Math.max(1, consultStore.countQueuedNowAheadOrEqual(consult.createdAt()));
      int avg = consultStore.rollingAvgCallDurationMinutes().orElse(DEFAULT_AVG_CALL_MINUTES);
      data.put("queue_position", position);
      data.put("estimated_wait_minutes", position * avg);
    } else {
      data.put("doctor", null);
    }
    data.put("scheduled_at", consult.scheduledAt());
    data.put("cart_id", consult.cartId());
    data.put("is_cart_mode", consult.cartMode());
    data.put("created_at", consult.createdAt());
    return data;
  }

  private Map<String, Object> toDetailResponse(Consult consult, TeleconsultDoctor doctor) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("consult_id", consult.id());
    data.put("doctor", doctor == null ? null : doctorCard(doctor));
    data.put("status", consult.status());
    data.put("scheduled_at", consult.scheduledAt());
    data.put("call_started_at", consult.callStartedAt());
    data.put("call_ended_at", consult.callEndedAt());
    data.put("e_prescription_id", consult.ePrescriptionId());
    data.put("cart_id", consult.cartId());
    data.put("is_cart_mode", consult.cartMode());
    data.put("patient_name", consult.patientName());
    data.put("created_at", consult.createdAt());
    return data;
  }

  private Map<String, Object> toListItem(ListItem item) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("consult_id", item.consultId());
    m.put("date", LocalDate.ofInstant(item.createdAt(), ZoneOffset.UTC).toString());
    m.put("doctor_name", item.doctorName());
    m.put("status", item.status());
    m.put("e_prescription_id", item.ePrescriptionId());
    m.put("cart_id", item.cartId());
    m.put("is_cart_mode", item.cartMode());
    m.put("rating_given", item.rating());
    m.put("created_at", item.createdAt());
    return m;
  }

  private static Map<String, Object> doctorCard(TeleconsultDoctor d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id());
    m.put("name", d.name());
    m.put("qualification", d.qualification());
    m.put("avatar_url", d.avatarUrl());
    m.put("registration_no", d.registrationNo());
    m.put("rating", d.avgRating());
    return m;
  }

  private Consult requireOwnConsult(UUID consultId, UUID customerId) {
    return consultStore
        .findByIdForCustomer(consultId, customerId)
        .orElseThrow(
            () ->
                new AppException("CONSULT_NOT_FOUND", "Consult not found for this customer", 404));
  }

  private static UUID requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
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

  private static String requireNonBlank(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    return raw.trim();
  }

  private static String requireConsultReason(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    String r = raw.trim().toUpperCase(Locale.ROOT);
    if (!CONSULT_REASONS.contains(r)) {
      throw new AppException("VALIDATION_ERROR", "reason must be GENERAL or RX_NEEDED", 400);
    }
    return r;
  }

  private static List<String> requireSymptoms(List<String> symptoms) {
    if (symptoms == null || symptoms.isEmpty()) {
      return List.of();
    }
    List<String> cleaned =
        symptoms.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    if (cleaned.size() > 10) {
      throw new AppException("VALIDATION_ERROR", "symptoms max 10", 400);
    }
    return cleaned;
  }

  private static List<MedicineNeed> requireMedicines(List<Map<String, Object>> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<MedicineNeed> out = new ArrayList<>();
    for (Map<String, Object> row : raw) {
      if (row == null) {
        continue;
      }
      Object nameObj = row.get("name");
      Object reasonObj = row.get("reason");
      String name = nameObj == null ? null : String.valueOf(nameObj).trim();
      String reason =
          reasonObj == null ? null : String.valueOf(reasonObj).trim().toUpperCase(Locale.ROOT);
      if (name == null || name.isBlank()) {
        throw new AppException("VALIDATION_ERROR", "medicines_needing_rx[].name is required", 400);
      }
      if (reason == null || !MED_REASONS.contains(reason)) {
        throw new AppException(
            "VALIDATION_ERROR",
            "medicines_needing_rx[].reason must be REFILL, NEW_SYMPTOMS, or DOCTOR_ADVISED",
            400);
      }
      out.add(new MedicineNeed(name, reason));
    }
    return out;
  }

  private SlotParsed parseSlot(String slot) {
    if (slot == null || slot.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "slot is required", 400);
    }
    String trimmed = slot.trim();
    if ("NOW".equalsIgnoreCase(trimmed)) {
      return new SlotParsed(Consult.SLOT_NOW, null);
    }
    try {
      Instant scheduled = Instant.parse(trimmed);
      if (!scheduled.isAfter(clock.instant())) {
        throw new AppException("VALIDATION_ERROR", "scheduled slot must be in the future", 400);
      }
      return new SlotParsed(Consult.SLOT_SCHEDULED, scheduled);
    } catch (DateTimeParseException ex) {
      throw new AppException("VALIDATION_ERROR", "slot must be NOW or an ISO-8601 datetime", 400);
    }
  }

  private static String normalizeListStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ALL";
    }
    String s = status.trim().toUpperCase(Locale.ROOT);
    if (!LIST_STATUSES.contains(s)) {
      throw new AppException(
          "VALIDATION_ERROR",
          "status must be ALL, REQUESTED, DOCTOR_REVIEWING, CALLING, IN_CALL, COMPLETED, or CANCELLED",
          400);
    }
    return s;
  }

  private record SlotParsed(String slotType, Instant scheduledAt) {}
}
