package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.AccountHealthService;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
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
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminCrmHealthControllerTest {

  @Mock AccountHealthService health;
  AdminCrmHealthController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminCrmHealthController(health);
  }

  @Test
  void delegatesAllEndpoints() {
    UUID accountId = Ids.newId();
    when(health.getHealth(any(), eq(accountId))).thenReturn(Map.of("overall_score", 42));
    when(health.listAtRisk(any(), isNull(), isNull(), isNull()))
        .thenReturn(
            new AccountHealthService.PagedResult(
                Map.of("accounts", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(health.logSavePlay(any(), eq(accountId), eq("CALL"), eq("ok"), isNull()))
        .thenReturn(Map.of("action_type", "CALL"));
    when(health.getUsage(any(), eq(accountId))).thenReturn(Map.of("modules", java.util.List.of()));
    when(health.healthKpis(any())).thenReturn(Map.of("at_risk_count", 1L));

    assertThat(controller.getHealth(principal, accountId).data())
        .containsEntry("overall_score", 42);
    assertThat(controller.listAtRisk(principal, null, null, null).data()).containsKey("accounts");
    assertThat(
            controller
                .savePlay(
                    principal,
                    accountId,
                    new AdminCrmHealthController.SavePlayRequest("CALL", "ok", null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.getUsage(principal, accountId).data()).containsKey("modules");
    assertThat(controller.healthKpis(principal).data()).containsEntry("at_risk_count", 1L);

    when(health.logSavePlay(any(), eq(accountId), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("action_type", "CALL"));
    assertThat(controller.savePlay(principal, accountId, null).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    verify(health).getHealth(principal, accountId);
  }
}
