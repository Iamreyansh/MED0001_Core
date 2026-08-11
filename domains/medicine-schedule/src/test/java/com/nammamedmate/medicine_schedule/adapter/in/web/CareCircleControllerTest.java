package com.nammamedmate.medicine_schedule.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.CareCircleService;
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
class CareCircleControllerTest {

  @Mock private CareCircleService service;

  private CareCircleController controller;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    controller = new CareCircleController(service);
    customer = new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void list_delegates() {
    when(service.list(customer)).thenReturn(Map.of("total_members", 1));
    ApiResponse<Map<String, Object>> response = controller.list(customer);
    assertThat(response.data()).containsEntry("total_members", 1);
    verify(service).list(customer);
  }

  @Test
  void create_delegates() {
    when(service.create(eq(customer), any())).thenReturn(Map.of("name", "Dad"));
    ApiResponse<Map<String, Object>> response =
        controller.create(
            customer, new CareCircleController.MemberRequest("Dad", 70, "PARENT", null, null));
    assertThat(response.data()).containsEntry("name", "Dad");
  }

  @Test
  void create_nullBody() {
    when(service.create(eq(customer), eq(null))).thenReturn(Map.of());
    controller.create(customer, null);
    verify(service).create(customer, null);
  }

  @Test
  void update_delete_summary() {
    UUID id = Ids.newId();
    when(service.update(eq(customer), eq(id), any())).thenReturn(Map.of("age", 71));
    when(service.delete(customer, id)).thenReturn(Map.of("member_id", id));
    when(service.summary(customer, id)).thenReturn(Map.of("medicines", java.util.List.of()));

    assertThat(
            controller
                .update(
                    customer,
                    id,
                    new CareCircleController.MemberRequest(null, 71, null, null, null))
                .data())
        .containsEntry("age", 71);
    assertThat(controller.delete(customer, id).data()).containsEntry("member_id", id);
    assertThat(controller.summary(customer, id).data()).containsKey("medicines");
    controller.update(customer, id, null);
    verify(service).update(eq(customer), eq(id), eq(null));
  }
}
