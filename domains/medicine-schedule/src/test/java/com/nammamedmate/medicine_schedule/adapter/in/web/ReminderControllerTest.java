package com.nammamedmate.medicine_schedule.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.DoseReminderService;
import com.nammamedmate.medicine_schedule.application.MedicineScheduleInternalAuth;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReminderControllerTest {

  @Mock private DoseReminderService service;
  @Mock private MedicineScheduleInternalAuth internalAuth;

  private ReminderController controller;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    controller = new ReminderController(service, internalAuth);
    customer = new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void bulkSchedule_requiresToken() {
    UUID customerId = Ids.newId();
    when(service.bulkSchedule(customerId, 7)).thenReturn(Map.of("reminders_created", 14));
    ReminderController.BulkScheduleRequest body =
        new ReminderController.BulkScheduleRequest(customerId, 7);
    assertThat(controller.bulkSchedule("tok", body).data()).containsEntry("reminders_created", 14);
    verify(internalAuth).require("tok");
  }

  @Test
  void bulkSchedule_nullBody() {
    when(service.bulkSchedule(isNull(), isNull())).thenReturn(Map.of());
    controller.bulkSchedule("tok", null);
    verify(service).bulkSchedule(null, null);
  }

  @Test
  void todayAndUpcoming() {
    UUID memberId = Ids.newId();
    when(service.today(customer, memberId)).thenReturn(Map.of("date", "2026-07-24"));
    when(service.upcoming(customer, memberId, 12)).thenReturn(Map.of("count", 2));
    assertThat(controller.today(customer, memberId).data()).containsEntry("date", "2026-07-24");
    assertThat(controller.upcoming(customer, memberId, 12).data()).containsEntry("count", 2);
  }
}
