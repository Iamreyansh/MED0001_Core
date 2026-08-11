package com.nammamedmate.medicine_schedule.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.medicine_schedule.application.AdherenceService;
import com.nammamedmate.medicine_schedule.application.DoseReminderService;
import com.nammamedmate.medicine_schedule.application.RefillAlertService;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService.CreateCommand;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService.DoseSlotInput;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService.UpdateCommand;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedule/medicines")
@Tag(name = "Schedule medicines")
public class ScheduleMedicineController {

  private final ScheduleMedicineService service;
  private final DoseReminderService doses;
  private final AdherenceService adherence;
  private final RefillAlertService refills;

  public ScheduleMedicineController(
      ScheduleMedicineService service,
      DoseReminderService doses,
      AdherenceService adherence,
      RefillAlertService refills) {
    this.service = service;
    this.doses = doses;
    this.adherence = adherence;
    this.refills = refills;
  }

  @GetMapping
  @Operation(summary = "List schedule medicines for a care circle member")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "member_id", required = false) UUID memberId,
      @RequestParam(value = "is_active", required = false) Boolean isActive) {
    return ApiResponse.ok(service.list(principal, memberId, isActive));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add a medicine to the schedule")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) MedicineRequest body) {
    return ApiResponse.ok(service.create(principal, toCreate(body)));
  }

  @GetMapping("/{medicine_id}")
  @Operation(summary = "Get medicine schedule detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("medicine_id") UUID medicineId) {
    return ApiResponse.ok(service.get(principal, medicineId));
  }

  @GetMapping("/{medicine_id}/adherence")
  @Operation(summary = "Per-medicine adherence history")
  public ApiResponse<Map<String, Object>> adherence(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("medicine_id") UUID medicineId) {
    return ApiResponse.ok(adherence.medicineAdherence(principal, medicineId));
  }

  @PatchMapping("/{medicine_id}")
  @Operation(summary = "Update medicine schedule")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("medicine_id") UUID medicineId,
      @RequestBody(required = false) MedicineRequest body) {
    return ApiResponse.ok(service.update(principal, medicineId, toUpdate(body)));
  }

  @DeleteMapping("/{medicine_id}")
  @Operation(summary = "Soft-delete (archive) a medicine from the schedule")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("medicine_id") UUID medicineId) {
    return ApiResponse.ok(service.delete(principal, medicineId));
  }

  @PostMapping("/{medicine_id}/doses/{date}/{slot}/mark")
  @Operation(summary = "Mark a dose as TAKEN or SKIPPED")
  public ApiResponse<Map<String, Object>> markDose(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("medicine_id") UUID medicineId,
      @PathVariable("date") String date,
      @PathVariable("slot") String slot,
      @RequestBody(required = false) MarkDoseRequest body) {
    MarkDoseRequest req = body == null ? new MarkDoseRequest(null, null) : body;
    Instant takenAt = null;
    if (req.takenAt() != null && !req.takenAt().isBlank()) {
      takenAt = Instant.parse(req.takenAt().trim());
    }
    return ApiResponse.ok(doses.markDose(principal, medicineId, date, slot, req.status(), takenAt));
  }

  @PostMapping("/{medicine_id}/refill")
  @Operation(summary = "Record a manual refill (additive)")
  public ApiResponse<Map<String, Object>> refill(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("medicine_id") UUID medicineId,
      @RequestBody(required = false) RefillRequest body) {
    RefillRequest req = body == null ? new RefillRequest(null, null) : body;
    return ApiResponse.ok(
        refills.recordRefill(principal, medicineId, req.unitsAdded(), req.refillDate()));
  }

  @PostMapping("/{medicine_id}/refill/order-online")
  @Operation(summary = "Get deep link to order medicine online")
  public ApiResponse<Map<String, Object>> orderOnline(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("medicine_id") UUID medicineId) {
    return ApiResponse.ok(refills.orderOnline(principal, medicineId));
  }

  private static CreateCommand toCreate(MedicineRequest body) {
    if (body == null) {
      return null;
    }
    return new CreateCommand(
        body.memberId(),
        body.medicineName(),
        body.masterMedicineId(),
        body.strength(),
        body.dose(),
        body.form(),
        toSlotInputs(body.doseSlots()),
        body.foodInstruction(),
        body.durationType(),
        body.durationDays(),
        body.startedOnDate(),
        body.conditionName(),
        body.prescribedByDoctor(),
        body.refillUnitsInHand(),
        body.refillRemindAtUnits(),
        body.notes());
  }

  private static UpdateCommand toUpdate(MedicineRequest body) {
    if (body == null) {
      return null;
    }
    return new UpdateCommand(
        body.medicineName(),
        body.masterMedicineId(),
        body.masterMedicineId() != null,
        body.strength(),
        body.dose(),
        body.form(),
        toSlotInputs(body.doseSlots()),
        body.foodInstruction(),
        body.durationType(),
        body.durationDays(),
        body.startedOnDate(),
        body.conditionName(),
        body.prescribedByDoctor(),
        body.refillUnitsInHand(),
        body.refillRemindAtUnits(),
        body.notes());
  }

  private static List<DoseSlotInput> toSlotInputs(List<DoseSlotRequest> slots) {
    if (slots == null) {
      return null;
    }
    return slots.stream()
        .map(s -> s == null ? null : new DoseSlotInput(s.slot(), s.reminderTime()))
        .toList();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MedicineRequest(
      UUID memberId,
      String medicineName,
      UUID masterMedicineId,
      String strength,
      String dose,
      String form,
      List<DoseSlotRequest> doseSlots,
      String foodInstruction,
      String durationType,
      Integer durationDays,
      String startedOnDate,
      String conditionName,
      String prescribedByDoctor,
      Integer refillUnitsInHand,
      Integer refillRemindAtUnits,
      String notes) {
    public MedicineRequest {
      doseSlots =
          doseSlots == null
              ? null
              : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(doseSlots));
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DoseSlotRequest(String slot, String reminderTime) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkDoseRequest(String status, String takenAt) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RefillRequest(Integer unitsAdded, String refillDate) {}
}
