package com.nammamedmate.teleconsult.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Patient teleconsult request (EPIC-009 STORY-002/003). */
public record Consult(
    UUID id,
    UUID customerId,
    UUID doctorId,
    String patientName,
    String patientPhone,
    String slotType,
    Instant scheduledAt,
    List<String> symptoms,
    List<MedicineNeed> medicinesNeedingRx,
    UUID cartId,
    boolean cartMode,
    String reason,
    String status,
    Instant callStartedAt,
    Instant callEndedAt,
    BigDecimal durationMinutes,
    UUID ePrescriptionId,
    boolean adviceOnly,
    String clinicalNotes,
    Integer rating,
    String feedbackText,
    Instant ratedAt,
    String autoCancelledReason,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public static final String SLOT_NOW = "NOW";
  public static final String SLOT_SCHEDULED = "SCHEDULED";

  public static final String STATUS_REQUESTED = "REQUESTED";
  public static final String STATUS_DOCTOR_REVIEWING = "DOCTOR_REVIEWING";
  public static final String STATUS_CALLING = "CALLING";
  public static final String STATUS_IN_CALL = "IN_CALL";
  public static final String STATUS_COMPLETED = "COMPLETED";
  public static final String STATUS_CANCELLED = "CANCELLED";

  public static final String REASON_GENERAL = "GENERAL";
  public static final String REASON_RX_NEEDED = "RX_NEEDED";

  private static final Map<String, Set<String>> TRANSITIONS =
      Map.of(
          STATUS_REQUESTED, Set.of(STATUS_DOCTOR_REVIEWING, STATUS_CANCELLED),
          STATUS_DOCTOR_REVIEWING, Set.of(STATUS_CALLING, STATUS_CANCELLED),
          STATUS_CALLING, Set.of(STATUS_IN_CALL, STATUS_CANCELLED),
          STATUS_IN_CALL, Set.of(STATUS_COMPLETED, STATUS_CANCELLED),
          STATUS_COMPLETED, Set.of(),
          STATUS_CANCELLED, Set.of());

  public Consult {
    symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
    medicinesNeedingRx = medicinesNeedingRx == null ? List.of() : List.copyOf(medicinesNeedingRx);
  }

  public boolean isActive() {
    return !STATUS_COMPLETED.equals(status) && !STATUS_CANCELLED.equals(status);
  }

  public boolean customerCancellable() {
    return STATUS_REQUESTED.equals(status) || STATUS_DOCTOR_REVIEWING.equals(status);
  }

  public static boolean canTransition(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    Set<String> allowed = TRANSITIONS.get(from);
    return allowed != null && allowed.contains(to);
  }

  public record MedicineNeed(String name, String reason) {}
}
