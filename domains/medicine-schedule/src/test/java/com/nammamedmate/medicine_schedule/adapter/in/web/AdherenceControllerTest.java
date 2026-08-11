package com.nammamedmate.medicine_schedule.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.AdherenceService;
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
class AdherenceControllerTest {

  @Mock private AdherenceService service;

  private AdherenceController controller;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    controller = new AdherenceController(service);
    customer = new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void summaryCalendarChart() {
    UUID memberId = Ids.newId();
    when(service.summary(customer, memberId)).thenReturn(Map.of("current_streak_days", 5));
    when(service.calendar(customer, memberId, "2026-07")).thenReturn(Map.of("month", "2026-07"));
    when(service.chart(customer, memberId, 4)).thenReturn(Map.of("weeks", java.util.List.of()));

    assertThat(controller.summary(customer, memberId).data())
        .containsEntry("current_streak_days", 5);
    assertThat(controller.calendar(customer, memberId, "2026-07").data())
        .containsEntry("month", "2026-07");
    assertThat(controller.chart(customer, memberId, 4).data()).containsKey("weeks");
  }
}
