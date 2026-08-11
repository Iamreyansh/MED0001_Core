package com.nammamedmate.medicine_schedule.application.port.out;

import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleMedicineStore {

  ScheduleMedicineRecord insert(ScheduleMedicineRecord medicine);

  ScheduleMedicineRecord update(ScheduleMedicineRecord medicine);

  Optional<ScheduleMedicineRecord> findById(UUID medicineId);

  List<ScheduleMedicineRecord> listByMember(UUID customerId, UUID memberId, boolean activeOnly);

  List<ScheduleMedicineRecord> listActiveByMember(UUID memberId);

  List<ScheduleMedicineRecord> listActiveByCustomer(UUID customerId);

  /** Distinct customer ids that still have at least one active medicine. */
  List<UUID> listCustomerIdsWithActiveMedicines();

  /** Decrement units_in_hand by 1 when currently > 0; returns rows updated (0 or 1). */
  int decrementUnitsInHand(UUID medicineId, Instant updatedAt);

  /**
   * Subtract {@code amount} from units_in_hand (clamped at 0). Returns new units, or empty if
   * medicine missing.
   */
  Optional<Integer> decrementUnitsBy(UUID medicineId, int amount, Instant updatedAt);

  /** Active medicines with units_in_hand &gt; 0 and refill_remind_at_units &gt; 0. */
  List<ScheduleMedicineRecord> listActiveWithSupplyTracking();

  /** Active medicines currently in refill alert that have not been pushed on {@code today}. */
  List<ScheduleMedicineRecord> listRefillAlertsNeedingPush(LocalDate today);

  void markRefillAlertPushedOn(UUID medicineId, LocalDate pushedOn, Instant updatedAt);

  /** Soft-archive active medicines for a member; returns archived count. */
  int softArchiveByMember(UUID memberId, LocalDate endedOn, Instant updatedAt);

  record ScheduleMedicineRecord(
      UUID id,
      UUID customerId,
      UUID memberId,
      UUID masterMedicineId,
      String medicineName,
      String strength,
      String dose,
      String form,
      List<DoseSlot> doseSlots,
      String foodInstruction,
      String durationType,
      Integer durationDays,
      LocalDate startedOnDate,
      LocalDate endedOnDate,
      String conditionName,
      String prescribedBy,
      int unitsInHand,
      int refillRemindAtUnits,
      String notes,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {
    public ScheduleMedicineRecord {
      doseSlots = doseSlots == null ? List.of() : List.copyOf(doseSlots);
    }
  }
}
