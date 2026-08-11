package com.nammamedmate.medicine_schedule.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.AdherenceService;
import com.nammamedmate.medicine_schedule.application.DoseReminderService;
import com.nammamedmate.medicine_schedule.application.RefillAlertService;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleMedicineControllerTest {

  @Mock private ScheduleMedicineService service;
  @Mock private DoseReminderService doses;
  @Mock private AdherenceService adherence;
  @Mock private RefillAlertService refills;

  private ScheduleMedicineController controller;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    controller = new ScheduleMedicineController(service, doses, adherence, refills);
    customer = new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void list_delegates() {
    when(service.list(eq(customer), isNull(), isNull())).thenReturn(Map.of("total_medicines", 1));
    ApiResponse<Map<String, Object>> response = controller.list(customer, null, null);
    assertThat(response.data()).containsEntry("total_medicines", 1);
  }

  @Test
  void create_get_update_delete() {
    UUID id = Ids.newId();
    when(service.create(eq(customer), any())).thenReturn(Map.of("medicine_id", id));
    when(service.get(customer, id)).thenReturn(Map.of("medicine_id", id));
    when(service.update(eq(customer), eq(id), any()))
        .thenReturn(Map.of("reminders_rescheduled", true));
    when(service.delete(customer, id)).thenReturn(Map.of("is_active", false));

    ScheduleMedicineController.MedicineRequest body =
        new ScheduleMedicineController.MedicineRequest(
            null,
            "Metformin",
            null,
            "500mg",
            "1 tablet",
            "TABLET",
            List.of(new ScheduleMedicineController.DoseSlotRequest("MORNING", "08:00")),
            "AFTER",
            "ONGOING",
            null,
            "2026-07-24",
            null,
            null,
            30,
            10,
            null);

    assertThat(controller.create(customer, body).data()).containsEntry("medicine_id", id);
    assertThat(controller.get(customer, id).data()).containsEntry("medicine_id", id);
    assertThat(controller.update(customer, id, body).data())
        .containsEntry("reminders_rescheduled", true);
    assertThat(controller.delete(customer, id).data()).containsEntry("is_active", false);
  }

  @Test
  void adherence_delegates() {
    UUID id = Ids.newId();
    when(adherence.medicineAdherence(customer, id)).thenReturn(Map.of("all_time_pct", 84.2));
    assertThat(controller.adherence(customer, id).data()).containsEntry("all_time_pct", 84.2);
  }

  @Test
  void create_nullBody() {
    when(service.create(eq(customer), isNull())).thenReturn(Map.of());
    controller.create(customer, null);
    verify(service).create(customer, null);
  }

  @Test
  void update_nullBody() {
    UUID id = Ids.newId();
    when(service.update(eq(customer), eq(id), isNull())).thenReturn(Map.of());
    controller.update(customer, id, null);
    verify(service).update(customer, id, null);
  }

  @Test
  void create_withMasterIdAndNullSlotEntry() {
    UUID master = Ids.newId();
    when(service.create(eq(customer), any())).thenReturn(Map.of("ok", true));
    ScheduleMedicineController.MedicineRequest body =
        new ScheduleMedicineController.MedicineRequest(
            null,
            "Med",
            master,
            null,
            "1",
            "TABLET",
            java.util.Arrays.asList(
                null, new ScheduleMedicineController.DoseSlotRequest("MORNING", "08:00")),
            "ANY",
            "ONGOING",
            null,
            "2026-07-24",
            null,
            null,
            null,
            null,
            null);
    assertThat(controller.create(customer, body).data()).containsEntry("ok", true);
    verify(service).create(eq(customer), any());
  }

  @Test
  void update_withMasterMedicineId() {
    UUID id = Ids.newId();
    UUID master = Ids.newId();
    when(service.update(eq(customer), eq(id), any())).thenReturn(Map.of("ok", true));
    ScheduleMedicineController.MedicineRequest body =
        new ScheduleMedicineController.MedicineRequest(
            null, null, master, null, null, null, null, null, null, null, null, null, null, null,
            null, null);
    assertThat(controller.update(customer, id, body).data()).containsEntry("ok", true);
  }

  @Test
  void markDose_delegates() {
    UUID id = Ids.newId();
    when(doses.markDose(eq(customer), eq(id), eq("2026-07-24"), eq("MORNING"), eq("TAKEN"), any()))
        .thenReturn(Map.of("status", "TAKEN"));
    ScheduleMedicineController.MarkDoseRequest body =
        new ScheduleMedicineController.MarkDoseRequest("TAKEN", "2026-07-24T08:05:00Z");
    assertThat(controller.markDose(customer, id, "2026-07-24", "MORNING", body).data())
        .containsEntry("status", "TAKEN");
  }

  @Test
  void markDose_nullTakenAtField() {
    UUID id = Ids.newId();
    when(doses.markDose(
            eq(customer), eq(id), eq("2026-07-24"), eq("MORNING"), eq("TAKEN"), isNull()))
        .thenReturn(Map.of("status", "TAKEN"));
    ScheduleMedicineController.MarkDoseRequest body =
        new ScheduleMedicineController.MarkDoseRequest("TAKEN", null);
    assertThat(controller.markDose(customer, id, "2026-07-24", "MORNING", body).data())
        .containsEntry("status", "TAKEN");
  }

  @Test
  void markDose_blankTakenAt() {
    UUID id = Ids.newId();
    when(doses.markDose(
            eq(customer), eq(id), eq("2026-07-24"), eq("MORNING"), eq("SKIPPED"), isNull()))
        .thenReturn(Map.of("status", "SKIPPED"));
    ScheduleMedicineController.MarkDoseRequest body =
        new ScheduleMedicineController.MarkDoseRequest("SKIPPED", "  ");
    assertThat(controller.markDose(customer, id, "2026-07-24", "MORNING", body).data())
        .containsEntry("status", "SKIPPED");
  }

  @Test
  void markDose_nullBody() {
    UUID id = Ids.newId();
    when(doses.markDose(eq(customer), eq(id), eq("2026-07-24"), eq("NIGHT"), isNull(), isNull()))
        .thenReturn(Map.of("status", "SKIPPED"));
    assertThat(controller.markDose(customer, id, "2026-07-24", "NIGHT", null).data())
        .containsEntry("status", "SKIPPED");
  }

  @Test
  void refill_andOrderOnline_delegate() {
    UUID id = Ids.newId();
    when(refills.recordRefill(eq(customer), eq(id), eq(60), eq("2026-07-24")))
        .thenReturn(Map.of("refill_alert_cleared", true));
    when(refills.orderOnline(customer, id)).thenReturn(Map.of("redirect_url", "medmate://x"));
    assertThat(
            controller
                .refill(
                    customer, id, new ScheduleMedicineController.RefillRequest(60, "2026-07-24"))
                .data())
        .containsEntry("refill_alert_cleared", true);
    assertThat(controller.orderOnline(customer, id).data()).containsKey("redirect_url");
    controller.refill(customer, id, null);
    verify(refills).recordRefill(customer, id, null, null);
  }
}
