package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.RenewalChurnService;
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
class AdminCrmRenewalControllerTest {

  @Mock RenewalChurnService renewalChurn;
  AdminCrmRenewalController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminCrmRenewalController(renewalChurn);
  }

  @Test
  void delegatesAllEndpoints() {
    UUID accountId = Ids.newId();
    when(renewalChurn.dashboard(any())).thenReturn(Map.of("chips", Map.of()));
    when(renewalChurn.listUpcoming(any(), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new RenewalChurnService.PagedResult(
                Map.of("upcoming_renewals", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(renewalChurn.manualRenew(any(), eq(accountId), eq(false), eq("r"), eq("idem-r")))
        .thenReturn(Map.of("invoice_id", Ids.newId()));
    when(renewalChurn.manualRenew(any(), eq(accountId), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("invoice_id", Ids.newId()));
    when(renewalChurn.logChurnSurvey(any(), eq(accountId), eq("PRICE"), isNull()))
        .thenReturn(Map.of("reason", "PRICE"));
    when(renewalChurn.logChurnSurvey(any(), eq(accountId), isNull(), isNull()))
        .thenReturn(Map.of("reason", "OTHER"));
    when(renewalChurn.churnAnalysis(any(), eq("last_90d")))
        .thenReturn(Map.of("period", "last_90d"));

    assertThat(controller.dashboard(principal).data()).containsKey("chips");
    assertThat(controller.upcoming(principal, null, null, null, null, null).data())
        .containsKey("upcoming_renewals");
    assertThat(
            controller
                .renew(
                    principal,
                    accountId,
                    "idem-r",
                    new AdminCrmRenewalController.RenewRequest(false, "r"))
                .data())
        .containsKey("invoice_id");
    assertThat(
            controller
                .churnSurvey(
                    principal,
                    accountId,
                    new AdminCrmRenewalController.ChurnSurveyRequest("PRICE", null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.churnAnalysis(principal, "last_90d").data())
        .containsEntry("period", "last_90d");

    assertThat(controller.renew(principal, accountId, null, null).data()).isNotNull();
    assertThat(controller.churnSurvey(principal, accountId, null).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    verify(renewalChurn).dashboard(principal);
  }
}
