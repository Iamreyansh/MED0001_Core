package com.nammamedmate.medicine_schedule.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.RefillAlertService;
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
class RefillAlertControllerTest {

  @Mock private RefillAlertService service;
  private RefillAlertController controller;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    controller = new RefillAlertController(service);
    customer = new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void list_andShare_delegate() {
    UUID memberId = Ids.newId();
    when(service.listAlerts(customer, memberId)).thenReturn(Map.of("refill_alerts_count", 1));
    when(service.createShareLink(customer, null)).thenReturn(Map.of("token", "abc"));
    ApiResponse<Map<String, Object>> list = controller.list(customer, memberId);
    assertThat(list.data()).containsEntry("refill_alerts_count", 1);
    assertThat(controller.share(customer, null).data()).containsEntry("token", "abc");
    verify(service).createShareLink(customer, null);
  }
}
