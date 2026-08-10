package com.nammamedmate.teleconsult.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.teleconsult.application.port.out.CartLinkPort;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.CreateRequest;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.Issued;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.MedicineLine;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issue e-prescription for a consult (EPIC-009 STORY-004). */
@Service
public class ConsultEPrescriptionService {

  private static final Set<String> ADMIN_ROLES =
      Set.of(AuthRole.ADMIN_SUPER.name(), AuthRole.ADMIN_OPERATIONS.name());
  private static final Set<String> UNITS = Set.of("tablets", "capsules", "ml", "sachets");

  private final ConsultStore consultStore;
  private final TeleconsultDoctorStore doctorStore;
  private final EPrescriptionWritePort ePrescriptionWrite;
  private final CartLinkPort cartLink;
  private final NotificationDispatchPort notifications;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public ConsultEPrescriptionService(
      ConsultStore consultStore,
      TeleconsultDoctorStore doctorStore,
      EPrescriptionWritePort ePrescriptionWrite,
      CartLinkPort cartLink,
      NotificationDispatchPort notifications,
      RateLimiter rateLimiter,
      Clock clock) {
    this.consultStore = consultStore;
    this.doctorStore = doctorStore;
    this.ePrescriptionWrite = ePrescriptionWrite;
    this.cartLink = cartLink;
    this.notifications = notifications;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> issue(
      MedmatePrincipal principal, UUID consultId, IssueRequest request) {
    UUID actorId = requireAdmin(principal);
    rateLimit("teleconsult:admin:eprescription:" + actorId, 10, 60);

    IssueRequest req = request == null ? new IssueRequest(null, false, null, null) : request;
    Consult existing =
        consultStore
            .findById(consultId)
            .orElseThrow(() -> new AppException("CONSULT_NOT_FOUND", "Consult ID not found", 404));

    if (existing.doctorId() == null) {
      throw new AppException("CONSULT_NOT_ASSIGNED", "Consult is not assigned to a doctor", 403);
    }
    if (existing.ePrescriptionId() != null) {
      throw new AppException(
          "CONSULT_ALREADY_HAS_EPRESCRIPTION", "e-Rx already issued for this consult", 409);
    }
    if (!Consult.STATUS_IN_CALL.equals(existing.status())
        && !Consult.STATUS_COMPLETED.equals(existing.status())) {
      throw new AppException(
          "CONSULT_NOT_COMPLETED", "Consult must be in IN_CALL or COMPLETED state", 422);
    }

    boolean adviceOnly = Boolean.TRUE.equals(req.adviceOnly());
    String adviceText = normalizeAdviceText(req.adviceText(), adviceOnly);
    List<MedicineLine> medicines = parseMedicines(req.medicines(), adviceOnly);
    String clinical = normalizeClinical(req.clinicalNotes());

    TeleconsultDoctor doctor =
        doctorStore
            .findById(existing.doctorId())
            .orElseThrow(
                () ->
                    new AppException(
                        "CONSULT_NOT_ASSIGNED", "Consult is not assigned to a doctor", 403));

    Instant now = clock.instant();
    UUID prescriptionId = Ids.newId();
    Issued issued =
        ePrescriptionWrite.create(
            new CreateRequest(
                prescriptionId,
                existing.customerId(),
                existing.id(),
                doctor.id(),
                doctor.name(),
                doctor.qualification(),
                doctor.registrationNo(),
                doctor.specialty(),
                existing.patientName(),
                medicines,
                adviceOnly,
                adviceText,
                clinical,
                now));

    Instant callStarted = existing.callStartedAt();
    Instant callEnded = existing.callEndedAt();
    BigDecimal duration = existing.durationMinutes();
    String status = existing.status();
    boolean completedTransition = false;

    if (Consult.STATUS_IN_CALL.equals(existing.status())) {
      status = Consult.STATUS_COMPLETED;
      callEnded = now;
      if (callStarted == null) {
        duration = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
      } else {
        long seconds = Math.max(0, Duration.between(callStarted, callEnded).getSeconds());
        duration =
            BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
      }
      completedTransition = true;
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
            status,
            callStarted,
            callEnded,
            duration,
            issued.prescriptionId(),
            adviceOnly,
            clinical != null ? clinical : existing.clinicalNotes(),
            existing.rating(),
            existing.feedbackText(),
            existing.ratedAt(),
            existing.autoCancelledReason(),
            existing.createdAt(),
            now,
            existing.deletedAt());
    consultStore.update(updated);

    if (completedTransition) {
      consultStore.insertStatusEvent(
          new ConsultStatusEvent(
              Ids.newId(),
              updated.id(),
              Consult.STATUS_IN_CALL,
              Consult.STATUS_COMPLETED,
              actorId,
              "e-prescription issued",
              now));
      bumpDoctorOnCompletion(doctor, callEnded, now);
      notifications.notifyConsultStatusUpdated(
          updated.customerId(), updated.id(), Consult.STATUS_COMPLETED);
    }

    boolean cartLinked = false;
    UUID cartId = null;
    if (updated.cartMode() && updated.cartId() != null) {
      cartLink.attachPrescription(updated.customerId(), updated.cartId(), issued.prescriptionId());
      cartLinked = true;
      cartId = updated.cartId();
    }

    Map<String, Object> doctorView = new LinkedHashMap<>();
    doctorView.put("id", doctor.id());
    doctorView.put("name", doctor.name());
    doctorView.put("qualification", doctor.qualification());
    doctorView.put("registration_no", doctor.registrationNo());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("prescription_id", issued.prescriptionId());
    data.put("rx_id", issued.rxId());
    data.put("rx_type", "E_PRESCRIPTION");
    data.put("consult_id", updated.id());
    data.put("doctor", doctorView);
    data.put("patient_name", updated.patientName());
    data.put("issued_at", issued.issuedAt());
    data.put("is_verified", true);
    data.put("seal", "VERIFIED");
    data.put("advice_only", adviceOnly);
    data.put(
        "medicines",
        issued.medicines().stream()
            .map(
                m -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("name", m.name());
                  row.put("dosage", m.dosage());
                  row.put("frequency", m.frequency());
                  row.put("quantity", m.quantity());
                  row.put("unit", m.unit());
                  row.put("duration_days", m.durationDays());
                  row.put("notes", m.notes());
                  return row;
                })
            .toList());
    data.put("advice_text", adviceText);
    data.put("digital_signature_hash", issued.digitalSignatureHash());
    data.put("expires_at", issued.expiresAt());
    data.put("cart_linked", cartLinked);
    data.put("cart_id", cartId);
    data.put(
        "download_url",
        "/api/v1/prescriptions/eprescriptions/" + issued.prescriptionId() + "/download");
    return data;
  }

  private void bumpDoctorOnCompletion(TeleconsultDoctor doctor, Instant callEndedAt, Instant now) {
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

  private static List<MedicineLine> parseMedicines(
      List<Map<String, Object>> raw, boolean adviceOnly) {
    if (adviceOnly) {
      return List.of();
    }
    if (raw == null || raw.isEmpty()) {
      throw new AppException(
          "MEDICINES_REQUIRED", "medicines is required when advice_only=false", 422);
    }
    List<MedicineLine> out = new ArrayList<>();
    for (Map<String, Object> row : raw) {
      out.add(parseOne(row));
    }
    return out;
  }

  private static MedicineLine parseOne(Map<String, Object> row) {
    if (row == null) {
      throw new AppException("VALIDATION_ERROR", "medicine entry required", 400);
    }
    String name = requireStr(row, "name");
    String dosage = requireStr(row, "dosage");
    String frequency = requireStr(row, "frequency");
    int quantity = requireInt(row, "quantity");
    if (quantity < 1) {
      throw new AppException("VALIDATION_ERROR", "quantity must be >= 1", 400);
    }
    String unit = requireStr(row, "unit").toLowerCase(Locale.ROOT);
    if (!UNITS.contains(unit)) {
      throw new AppException("VALIDATION_ERROR", "unit must be tablets|capsules|ml|sachets", 400);
    }
    Integer duration = row.get("duration_days") == null ? null : requireInt(row, "duration_days");
    String notes = java.util.Objects.toString(row.get("notes"), "").trim();
    notes = notes.isEmpty() ? null : notes;
    return new MedicineLine(name, dosage, frequency, quantity, unit, duration, notes);
  }

  private static String normalizeAdviceText(String raw, boolean adviceOnly) {
    if (!adviceOnly) {
      return raw == null || raw.isBlank() ? null : raw.trim();
    }
    if (raw == null || raw.isBlank()) {
      throw new AppException(
          "ADVICE_TEXT_REQUIRED", "advice_text is required when advice_only=true", 422);
    }
    String text = raw.trim();
    if (text.length() > 1000) {
      throw new AppException("VALIDATION_ERROR", "advice_text max 1000 characters", 400);
    }
    return text;
  }

  private static String normalizeClinical(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return raw.trim();
  }

  private static String requireStr(Map<String, Object> row, String key) {
    Object v = row.get(key);
    if (v == null || v.toString().isBlank()) {
      throw new AppException("VALIDATION_ERROR", key + " is required", 400);
    }
    return v.toString().trim();
  }

  private static int requireInt(Map<String, Object> row, String key) {
    Object v = row.get(key);
    if (v == null) {
      throw new AppException("VALIDATION_ERROR", key + " is required", 400);
    }
    if (v instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(v.toString().trim());
    } catch (NumberFormatException e) {
      throw new AppException("VALIDATION_ERROR", key + " must be an integer", 400);
    }
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

  public record IssueRequest(
      List<Map<String, Object>> medicines,
      Boolean adviceOnly,
      String adviceText,
      String clinicalNotes) {
    public IssueRequest {
      // unmodifiable ArrayList (not List.copyOf) so null medicine entries can be validated
      medicines =
          medicines == null
              ? null
              : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(medicines));
    }
  }
}
