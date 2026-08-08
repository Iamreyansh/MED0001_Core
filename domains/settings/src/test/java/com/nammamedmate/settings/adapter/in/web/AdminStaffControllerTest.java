package com.nammamedmate.settings.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.AdminStaffService;
import com.nammamedmate.settings.application.AdminStaffService.ListResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminStaffControllerTest {

  private AdminStaffService service;
  private AdminStaffController controller;
  private MedmatePrincipal principal;

  @BeforeEach
  void setUp() {
    service = mock(AdminStaffService.class);
    controller = new AdminStaffController(service);
    principal = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void listDelegates() {
    when(service.list(eq(principal), eq(1), eq(20), eq(null), eq(null), eq(null)))
        .thenReturn(new ListResult(List.of(Map.of("id", "x")), PaginationMeta.of(1, 20, 1)));
    ApiResponse<List<Map<String, Object>>> res =
        controller.list(principal, 1, 20, null, null, null);
    assertThat(res.success()).isTrue();
    assertThat(res.data()).hasSize(1);
  }

  @Test
  void inviteDelegatesWithNullBody() {
    when(service.invite(eq(principal), any(), any(), any(), any())).thenReturn(Map.of("id", "1"));
    assertThat(controller.invite(principal, null).data()).containsEntry("id", "1");
    verify(service).invite(principal, null, null, null, null);
  }

  @Test
  void inviteDelegatesWithBody() {
    when(service.invite(principal, "N", "e@t.in", "admin_support", true))
        .thenReturn(Map.of("ok", true));
    var body = new AdminStaffController.InviteRequest("N", "e@t.in", "admin_support", true);
    assertThat(controller.invite(principal, body).success()).isTrue();
  }

  @Test
  void getUpdateDeleteReset() {
    UUID id = Ids.newId();
    when(service.get(principal, id)).thenReturn(Map.of("id", id));
    when(service.update(principal, id, "N", null, null)).thenReturn(Map.of("name", "N"));
    when(service.update(principal, id, null, null, null)).thenReturn(Map.of("name", "N"));
    when(service.delete(principal, id)).thenReturn(Map.of("id", id));
    when(service.resetPassword(principal, id)).thenReturn(Map.of("message", "sent"));

    assertThat(controller.get(principal, id).data()).containsEntry("id", id);
    assertThat(
            controller
                .update(principal, id, new AdminStaffController.UpdateRequest("N", null, null))
                .data())
        .containsEntry("name", "N");
    assertThat(controller.update(principal, id, null).data()).containsEntry("name", "N");
    assertThat(controller.delete(principal, id).data()).containsEntry("id", id);
    assertThat(controller.resetPassword(principal, id).data()).containsEntry("message", "sent");
  }
}
