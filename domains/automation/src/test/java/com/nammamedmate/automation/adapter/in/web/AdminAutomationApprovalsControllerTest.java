package com.nammamedmate.automation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.ApprovalQueueService;
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
class AdminAutomationApprovalsControllerTest {

  @Mock ApprovalQueueService approvals;
  @InjectMocks AdminAutomationApprovalsController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegates() {
    UUID id = UUID.randomUUID();
    when(approvals.list(any(), any(), any(), any(), any()))
        .thenReturn(
            new ApprovalQueueService.PagedResult(
                Map.of("approvals", List.of()), PaginationMeta.of(1, 20, 0)));
    when(approvals.get(any(), eq(id))).thenReturn(Map.of("approval_id", id));
    when(approvals.stats(any())).thenReturn(Map.of("period_days", 7));
    when(approvals.approve(any(), eq(id), any())).thenReturn(Map.of("status", "APPROVED"));
    when(approvals.reject(any(), eq(id), any())).thenReturn(Map.of("status", "REJECTED"));

    assertThat(controller.list(principal, "PENDING", "URGENT", 1, 20).success()).isTrue();
    assertThat(controller.get(principal, id).data()).containsEntry("approval_id", id);
    assertThat(controller.stats(principal).data()).containsEntry("period_days", 7);
    assertThat(controller.approve(principal, id, null).data()).containsEntry("status", "APPROVED");
    assertThat(
            controller
                .approve(principal, id, new AdminAutomationApprovalsController.ApproveRequest("n"))
                .success())
        .isTrue();
    verify(approvals).approve(principal, id, "n");
    assertThat(controller.reject(principal, id, null).data()).containsEntry("status", "REJECTED");
    assertThat(
            controller
                .reject(principal, id, new AdminAutomationApprovalsController.RejectRequest("r"))
                .success())
        .isTrue();
    verify(approvals).reject(principal, id, "r");
  }
}
