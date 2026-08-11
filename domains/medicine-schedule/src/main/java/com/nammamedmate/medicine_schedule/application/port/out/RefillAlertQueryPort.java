package com.nammamedmate.medicine_schedule.application.port.out;

import java.util.List;
import java.util.UUID;

/** Refill alerts + medicine rows for member summary. */
public interface RefillAlertQueryPort {

  List<RefillAlert> refillAlerts(UUID memberId);

  List<MedicineSummary> medicines(UUID memberId);

  Double thisWeekAdherencePct(UUID memberId);

  record RefillAlert(
      UUID medicineId,
      String medicineName,
      String strength,
      String form,
      int unitsInHand,
      int refillRemindAtUnits,
      int dosesPerDay,
      Integer approxDaysLeft,
      UUID masterMedicineId,
      boolean canOrderOnline,
      String alertLevel) {}

  record MedicineSummary(
      UUID medicineId,
      String medicineName,
      String dose,
      String form,
      List<DoseSlot> doseSlots,
      boolean active) {
    public MedicineSummary {
      doseSlots = doseSlots == null ? List.of() : List.copyOf(doseSlots);
    }
  }

  record DoseSlot(String slot, String reminderTime) {}
}
