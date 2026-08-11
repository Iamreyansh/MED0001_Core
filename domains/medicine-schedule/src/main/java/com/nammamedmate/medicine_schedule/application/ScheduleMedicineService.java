package com.nammamedmate.medicine_schedule.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderRecalcPort;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.AdherenceMath;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import com.nammamedmate.medicine_schedule.domain.DurationType;
import com.nammamedmate.medicine_schedule.domain.FoodInstruction;
import com.nammamedmate.medicine_schedule.domain.MedicineForm;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleMedicineService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int MAX_DOSE_SLOTS = 6;

  private final ScheduleMedicineStore store;
  private final CareCircleMemberStore members;
  private final CareCircleService careCircle;
  private final ReminderRecalcPort reminders;
  private final DoseLogStore doseLogs;
  private final Clock clock;

  public ScheduleMedicineService(
      ScheduleMedicineStore store,
      CareCircleMemberStore members,
      CareCircleService careCircle,
      ReminderRecalcPort reminders,
      DoseLogStore doseLogs,
      Clock clock) {
    this.store = store;
    this.members = members;
    this.careCircle = careCircle;
    this.reminders = reminders;
    this.doseLogs = doseLogs;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    UUID customerId = requireCustomerId(principal);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }
    MemberRecord member = resolveMemberForWrite(customerId, cmd.memberId());
    List<DoseSlot> slots = requireDoseSlots(cmd.doseSlots());
    String medicineName = requireMedicineName(cmd.medicineName());
    String dose = requireDose(cmd.dose());
    MedicineForm form;
    FoodInstruction food;
    DurationType durationType;
    try {
      form = MedicineForm.parse(cmd.form());
      food = FoodInstruction.parse(cmd.foodInstruction());
      durationType = DurationType.parse(cmd.durationType());
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
    }
    LocalDate started = requireStartedOn(cmd.startedOnDate());
    Integer durationDays = resolveDurationDays(durationType, cmd.durationDays());
    LocalDate ended = durationType == DurationType.DAYS ? started.plusDays(durationDays) : null;

    Instant now = clock.instant();
    ScheduleMedicineRecord saved =
        store.insert(
            new ScheduleMedicineRecord(
                Ids.newId(),
                customerId,
                member.id(),
                cmd.masterMedicineId(),
                medicineName,
                trimOptional(cmd.strength(), 50),
                dose,
                form.name(),
                slots,
                food.name(),
                durationType.name(),
                durationDays,
                started,
                ended,
                trimOptional(cmd.conditionName(), 200),
                trimOptional(cmd.prescribedByDoctor(), 200),
                nonNeg(cmd.refillUnitsInHand(), "refill_units_in_hand"),
                nonNeg(cmd.refillRemindAtUnits(), "refill_remind_at_units"),
                trimOptional(cmd.notes(), 500),
                true,
                now,
                now));

    int scheduled = reminders.recalculate(saved.id());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", saved.id());
    data.put("medicine_name", saved.medicineName());
    data.put("member_id", saved.memberId());
    data.put("dose_slots", toSlotViews(saved.doseSlots()));
    data.put("duration_type", saved.durationType());
    data.put("started_on_date", saved.startedOnDate().toString());
    data.put("reminders_scheduled", scheduled);
    data.put("created_at", saved.createdAt());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> list(MedmatePrincipal principal, UUID memberId, Boolean isActive) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member;
    if (memberId == null) {
      member = members.findSelf(customerId).orElseGet(() -> careCircle.ensureSelf(customerId));
    } else {
      member = requireOwnedMember(memberId, customerId);
    }
    boolean activeOnly = isActive == null || isActive;
    List<ScheduleMedicineRecord> rows = store.listByMember(customerId, member.id(), activeOnly);

    List<Map<String, Object>> medicines = new ArrayList<>(rows.size());
    for (ScheduleMedicineRecord m : rows) {
      medicines.add(toListItem(m));
    }

    Map<String, Object> memberView = new LinkedHashMap<>();
    memberView.put("member_id", member.id());
    memberView.put("name", member.name());
    memberView.put("relationship", member.relationship());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member", memberView);
    data.put("medicines", medicines);
    data.put("total_medicines", medicines.size());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID medicineId) {
    UUID customerId = requireCustomerId(principal);
    ScheduleMedicineRecord medicine = requireOwnedMedicine(medicineId, customerId);
    MemberRecord member = requireOwnedMember(medicine.memberId(), customerId);

    Map<String, Object> memberView = new LinkedHashMap<>();
    memberView.put("member_id", member.id());
    memberView.put("name", member.name());
    memberView.put("relationship", member.relationship());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", medicine.id());
    data.put("member", memberView);
    data.put("medicine_name", medicine.medicineName());
    data.put("strength", medicine.strength());
    data.put("dose", medicine.dose());
    data.put("form", medicine.form());
    data.put("dose_slots", toSlotViews(medicine.doseSlots()));
    data.put("food_instruction", medicine.foodInstruction());
    data.put("duration_type", medicine.durationType());
    data.put("started_on_date", medicine.startedOnDate().toString());
    data.put(
        "ended_on_date", medicine.endedOnDate() == null ? null : medicine.endedOnDate().toString());
    data.put("condition_name", medicine.conditionName());
    data.put("prescribed_by", medicine.prescribedBy());
    data.put("units_in_hand", medicine.unitsInHand());
    data.put("refill_remind_at_units", medicine.refillRemindAtUnits());
    data.put("approx_days_left", approxDaysLeft(medicine));
    data.put("today_doses", todayDosesDetail(medicine.id()));
    data.put("course_progress_pct", null);
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    TodayCounts week = doseLogs.countsForMedicineBetween(medicine.id(), weekStart, today);
    data.put("this_week_adherence_pct", AdherenceMath.pct(week.taken(), week.total()));
    data.put("notes", medicine.notes());
    data.put("is_active", medicine.active());
    data.put("created_at", medicine.createdAt());
    return data;
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal, UUID medicineId, UpdateCommand cmd) {
    UUID customerId = requireCustomerId(principal);
    ScheduleMedicineRecord existing = requireOwnedMedicine(medicineId, customerId);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }

    List<String> updatedFields = new ArrayList<>();
    String medicineName = existing.medicineName();
    if (cmd.medicineName() != null) {
      medicineName = requireMedicineName(cmd.medicineName());
      updatedFields.add("medicine_name");
    }
    UUID masterMedicineId = existing.masterMedicineId();
    if (cmd.masterMedicineIdProvided()) {
      masterMedicineId = cmd.masterMedicineId();
      updatedFields.add("master_medicine_id");
    }
    String strength = existing.strength();
    if (cmd.strength() != null) {
      strength = trimOptional(cmd.strength(), 50);
      updatedFields.add("strength");
    }
    String dose = existing.dose();
    if (cmd.dose() != null) {
      dose = requireDose(cmd.dose());
      updatedFields.add("dose");
    }
    String form = existing.form();
    if (cmd.form() != null) {
      try {
        form = MedicineForm.parse(cmd.form()).name();
      } catch (IllegalArgumentException ex) {
        throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
      }
      updatedFields.add("form");
    }
    List<DoseSlot> slots = existing.doseSlots();
    boolean slotsChanged = false;
    if (cmd.doseSlots() != null) {
      slots = requireDoseSlots(cmd.doseSlots());
      slotsChanged = !Objects.equals(slots, existing.doseSlots());
      updatedFields.add("dose_slots");
    }
    String food = existing.foodInstruction();
    if (cmd.foodInstruction() != null) {
      try {
        food = FoodInstruction.parse(cmd.foodInstruction()).name();
      } catch (IllegalArgumentException ex) {
        throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
      }
      updatedFields.add("food_instruction");
    }
    String durationType = existing.durationType();
    Integer durationDays = existing.durationDays();
    LocalDate started = existing.startedOnDate();
    LocalDate ended = existing.endedOnDate();
    if (cmd.durationType() != null || cmd.durationDays() != null || cmd.startedOnDate() != null) {
      DurationType dt;
      try {
        dt =
            cmd.durationType() == null
                ? DurationType.parse(existing.durationType())
                : DurationType.parse(cmd.durationType());
      } catch (IllegalArgumentException ex) {
        throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
      }
      if (cmd.durationType() != null) {
        durationType = dt.name();
        updatedFields.add("duration_type");
      }
      if (cmd.startedOnDate() != null) {
        started = requireStartedOn(cmd.startedOnDate());
        updatedFields.add("started_on_date");
      }
      Integer daysInput = cmd.durationDays() != null ? cmd.durationDays() : existing.durationDays();
      durationDays = resolveDurationDays(dt, daysInput);
      if (cmd.durationDays() != null) {
        updatedFields.add("duration_days");
      }
      ended = dt == DurationType.DAYS ? started.plusDays(durationDays) : null;
      if (dt == DurationType.ONGOING) {
        durationDays = null;
      }
    }
    String conditionName = existing.conditionName();
    if (cmd.conditionName() != null) {
      conditionName = trimOptional(cmd.conditionName(), 200);
      updatedFields.add("condition_name");
    }
    String prescribedBy = existing.prescribedBy();
    if (cmd.prescribedByDoctor() != null) {
      prescribedBy = trimOptional(cmd.prescribedByDoctor(), 200);
      updatedFields.add("prescribed_by");
    }
    int units = existing.unitsInHand();
    if (cmd.refillUnitsInHand() != null) {
      units = nonNeg(cmd.refillUnitsInHand(), "refill_units_in_hand");
      updatedFields.add("units_in_hand");
    }
    int refillAt = existing.refillRemindAtUnits();
    if (cmd.refillRemindAtUnits() != null) {
      refillAt = nonNeg(cmd.refillRemindAtUnits(), "refill_remind_at_units");
      updatedFields.add("refill_remind_at_units");
    }
    String notes = existing.notes();
    if (cmd.notes() != null) {
      notes = trimOptional(cmd.notes(), 500);
      updatedFields.add("notes");
    }

    Instant now = clock.instant();
    ScheduleMedicineRecord updated =
        store.update(
            new ScheduleMedicineRecord(
                existing.id(),
                existing.customerId(),
                existing.memberId(),
                masterMedicineId,
                medicineName,
                strength,
                dose,
                form,
                slots,
                food,
                durationType,
                durationDays,
                started,
                ended,
                conditionName,
                prescribedBy,
                units,
                refillAt,
                notes,
                existing.active(),
                existing.createdAt(),
                now));

    boolean rescheduled = false;
    if (slotsChanged) {
      reminders.cancelFuture(updated.id());
      reminders.recalculate(updated.id());
      rescheduled = true;
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", updated.id());
    data.put("updated_fields", updatedFields);
    data.put("reminders_rescheduled", rescheduled);
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID medicineId) {
    UUID customerId = requireCustomerId(principal);
    ScheduleMedicineRecord existing = requireOwnedMedicine(medicineId, customerId);
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, IST);
    store.update(
        new ScheduleMedicineRecord(
            existing.id(),
            existing.customerId(),
            existing.memberId(),
            existing.masterMedicineId(),
            existing.medicineName(),
            existing.strength(),
            existing.dose(),
            existing.form(),
            existing.doseSlots(),
            existing.foodInstruction(),
            existing.durationType(),
            existing.durationDays(),
            existing.startedOnDate(),
            today,
            existing.conditionName(),
            existing.prescribedBy(),
            existing.unitsInHand(),
            existing.refillRemindAtUnits(),
            existing.notes(),
            false,
            existing.createdAt(),
            now));
    int cancelled = reminders.cancelFuture(existing.id());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", existing.id());
    data.put("is_active", false);
    data.put("ended_on_date", today.toString());
    data.put("reminders_cancelled", cancelled);
    return data;
  }

  private MemberRecord resolveMemberForWrite(UUID customerId, UUID memberId) {
    if (memberId == null) {
      return careCircle.ensureSelf(customerId);
    }
    return requireOwnedMember(memberId, customerId);
  }

  private MemberRecord requireOwnedMember(UUID memberId, UUID customerId) {
    MemberRecord member =
        members
            .findById(memberId)
            .orElseThrow(() -> new AppException("MEMBER_NOT_FOUND", "Member not found", 404));
    if (!member.customerId().equals(customerId)) {
      throw new AppException(
          "MEMBER_ACCESS_DENIED", "Member does not belong to this customer", 403);
    }
    return member;
  }

  private ScheduleMedicineRecord requireOwnedMedicine(UUID medicineId, UUID customerId) {
    ScheduleMedicineRecord medicine =
        store
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (!medicine.customerId().equals(customerId)) {
      throw new AppException(
          "MEDICINE_ACCESS_DENIED", "Medicine does not belong to this customer", 403);
    }
    return medicine;
  }

  private static UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }

  private static List<DoseSlot> requireDoseSlots(List<DoseSlotInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "dose_slots requires at least 1 entry", 400);
    }
    if (inputs.size() > MAX_DOSE_SLOTS) {
      throw new AppException(
          "TOO_MANY_DOSE_SLOTS", "A medicine may have at most 6 dose slots", 400);
    }
    List<DoseSlot> slots = new ArrayList<>(inputs.size());
    for (DoseSlotInput input : inputs) {
      if (input == null) {
        throw new AppException("VALIDATION_ERROR", "dose_slots entry is required", 400);
      }
      try {
        slots.add(new DoseSlot(input.slot(), input.reminderTime()));
      } catch (IllegalArgumentException ex) {
        if ("INVALID_REMINDER_TIME".equals(ex.getMessage())) {
          throw new AppException(
              "INVALID_REMINDER_TIME", "reminder_time must be HH:MM 24-hour format", 400);
        }
        throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
      }
    }
    return List.copyOf(slots);
  }

  private static Integer resolveDurationDays(DurationType type, Integer durationDays) {
    if (type == DurationType.DAYS) {
      if (durationDays == null || durationDays <= 0) {
        throw new AppException(
            "MISSING_DURATION_DAYS", "duration_days is required when duration_type is DAYS", 400);
      }
      return durationDays;
    }
    return null;
  }

  private static String requireMedicineName(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "medicine_name is required", 400);
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 200) {
      throw new AppException("VALIDATION_ERROR", "medicine_name max length is 200", 400);
    }
    return trimmed;
  }

  private static String requireDose(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "dose is required", 400);
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "dose max length is 100", 400);
    }
    return trimmed;
  }

  private static LocalDate requireStartedOn(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "started_on_date is required", 400);
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", "started_on_date must be YYYY-MM-DD", 400);
    }
  }

  private static String trimOptional(String raw, int max) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > max) {
      throw new AppException("VALIDATION_ERROR", "field max length is " + max, 400);
    }
    return trimmed;
  }

  private static int nonNeg(Integer value, String field) {
    if (value == null) {
      return 0;
    }
    if (value < 0) {
      throw new AppException("VALIDATION_ERROR", field + " must be >= 0", 400);
    }
    return value;
  }

  private static Integer approxDaysLeft(ScheduleMedicineRecord medicine) {
    int dosesPerDay = medicine.doseSlots().size();
    if (dosesPerDay == 0) {
      return null;
    }
    return medicine.unitsInHand() / dosesPerDay;
  }

  private Map<String, Object> toListItem(ScheduleMedicineRecord m) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("medicine_id", m.id());
    row.put("medicine_name", m.medicineName());
    row.put("master_medicine_id", m.masterMedicineId());
    row.put("strength", m.strength());
    row.put("dose", m.dose());
    row.put("form", m.form());
    row.put("dose_slots", toSlotViews(m.doseSlots()));
    row.put("food_instruction", m.foodInstruction());
    row.put("duration_type", m.durationType());
    row.put("started_on_date", m.startedOnDate().toString());
    row.put("ended_on_date", m.endedOnDate() == null ? null : m.endedOnDate().toString());
    row.put("condition_name", m.conditionName());
    row.put("prescribed_by", m.prescribedBy());
    row.put("units_in_hand", m.unitsInHand());
    row.put("refill_remind_at_units", m.refillRemindAtUnits());
    row.put("is_active", m.active());
    row.put("today_doses", todayDosesList(m.id()));
    return row;
  }

  private static List<Map<String, Object>> toSlotViews(List<DoseSlot> slots) {
    List<Map<String, Object>> views = new ArrayList<>(slots.size());
    for (DoseSlot s : slots) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("slot", s.slot());
      row.put("reminder_time", s.reminderTime());
      views.add(row);
    }
    return views;
  }

  private Map<String, Object> todayDosesList(UUID medicineId) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    TodayCounts c = doseLogs.countsForMedicineOn(medicineId, today);
    Map<String, Object> doses = new LinkedHashMap<>();
    doses.put("total", c.total());
    doses.put("taken", c.taken());
    doses.put("upcoming", c.upcoming());
    return doses;
  }

  private Map<String, Object> todayDosesDetail(UUID medicineId) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    TodayCounts c = doseLogs.countsForMedicineOn(medicineId, today);
    Map<String, Object> doses = new LinkedHashMap<>();
    doses.put("total", c.total());
    doses.put("taken", c.taken());
    doses.put("skipped", c.skipped());
    doses.put("missed", c.missed());
    doses.put("upcoming", c.upcoming());
    return doses;
  }

  public record DoseSlotInput(String slot, String reminderTime) {}

  public record CreateCommand(
      UUID memberId,
      String medicineName,
      UUID masterMedicineId,
      String strength,
      String dose,
      String form,
      List<DoseSlotInput> doseSlots,
      String foodInstruction,
      String durationType,
      Integer durationDays,
      String startedOnDate,
      String conditionName,
      String prescribedByDoctor,
      Integer refillUnitsInHand,
      Integer refillRemindAtUnits,
      String notes) {
    public CreateCommand {
      doseSlots =
          doseSlots == null
              ? null
              : java.util.Collections.unmodifiableList(new ArrayList<>(doseSlots));
    }
  }

  public record UpdateCommand(
      String medicineName,
      UUID masterMedicineId,
      boolean masterMedicineIdProvided,
      String strength,
      String dose,
      String form,
      List<DoseSlotInput> doseSlots,
      String foodInstruction,
      String durationType,
      Integer durationDays,
      String startedOnDate,
      String conditionName,
      String prescribedByDoctor,
      Integer refillUnitsInHand,
      Integer refillRemindAtUnits,
      String notes) {
    public UpdateCommand {
      doseSlots =
          doseSlots == null
              ? null
              : java.util.Collections.unmodifiableList(new ArrayList<>(doseSlots));
    }
  }
}
