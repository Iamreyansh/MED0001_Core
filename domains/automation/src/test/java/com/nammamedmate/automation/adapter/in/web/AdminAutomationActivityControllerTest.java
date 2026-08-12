package com.nammamedmate.automation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.ActivityLogService;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAutomationActivityControllerTest {

  @Mock ActivityLogService activity;
  @InjectMocks AdminAutomationActivityController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegates() {
    UUID id = UUID.randomUUID();
    when(activity.list(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ActivityLogService.PagedResult(
                Map.of("activity", List.of()), PaginationMeta.of(1, 20, 0)));
    when(activity.get(any(), eq(id))).thenReturn(Map.of("action_id", id));
    when(activity.stats(any())).thenReturn(Map.of("rules_active", 1L));
    when(activity.rollback(any(), eq(id), any())).thenReturn(Map.of("action_type", "ROLLBACK"));

    assertThat(
            controller
                .list(principal, "EXECUTED", id, "DISPATCH", "ORDER", null, null, 1, 20)
                .success())
        .isTrue();
    assertThat(controller.get(principal, id).data()).containsEntry("action_id", id);
    assertThat(controller.stats(principal).data()).containsEntry("rules_active", 1L);
    assertThat(controller.rollback(principal, id, null).data())
        .containsEntry("action_type", "ROLLBACK");
    assertThat(
            controller
                .rollback(principal, id, new AdminAutomationActivityController.RollbackRequest("r"))
                .success())
        .isTrue();
    verify(activity).rollback(principal, id, "r");
  }
}
