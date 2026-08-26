package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyCommissionController;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyCommissionController.ChangeCommissionRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyCommissionController.HoldSettlementRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyCommissionController.ReleaseSettlementRequest;
import com.nammamedmate.pharmacy.adapter.in.web.CashfreePayoutWebhookController;
import com.nammamedmate.pharmacy.application.AdminPharmacyCommissionService;
import com.nammamedmate.pharmacy.application.AdminPharmacySettlementService;
import com.nammamedmate.pharmacy.application.AdminPharmacySettlementService.PagedResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminPharmacyCommissionAdapterCoverageTest {

  private static final UUID PID = Ids.newId();
  private static final UUID SID = Ids.newId();
  private static final UUID ADMIN = Ids.newId();

  @Test
  void commissionControllerDelegatesAllEndpoints() {
    AdminPharmacyCommissionService commission = mock(AdminPharmacyCommissionService.class);
    AdminPharmacySettlementService settlement = mock(AdminPharmacySettlementService.class);
    when(commission.getCommission(any(), any())).thenReturn(Map.of("pharmacy_id", PID.toString()));
    when(commission.changeCommission(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("commission_history_id", SID.toString()));
    when(settlement.listSettlements(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new PagedResult(Map.of("settlements", List.of()), PaginationMeta.of(1, 20, 0)));
    when(settlement.release(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "RELEASED"));
    when(settlement.hold(any(), any(), any(), any())).thenReturn(Map.of("status", "HELD"));

    AdminPharmacyCommissionController controller =
        new AdminPharmacyCommissionController(commission, settlement);
    MedmatePrincipal principal =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    MockHttpServletRequest request = new MockHttpServletRequest();

    request.setRemoteAddr("203.0.113.10");
    assertThat(controller.getCommission(principal, PID).success()).isTrue();
    request.setRemoteAddr("  203.0.113.10  ");
    assertThat(
            controller
                .changeCommission(
                    principal,
                    PID,
                    new ChangeCommissionRequest(
                        new BigDecimal("7.00"), LocalDate.parse("2026-07-28"), "reason", null),
                    request)
                .success())
        .isTrue();
    request.setRemoteAddr(null);
    assertThat(controller.changeCommission(principal, PID, null, request).success()).isTrue();
    request.setRemoteAddr("");
    assertThat(
            controller
                .changeCommission(
                    principal,
                    PID,
                    new ChangeCommissionRequest(
                        new BigDecimal("7.00"), LocalDate.parse("2026-07-28"), "reason", null),
                    request)
                .success())
        .isTrue();
    request.setRemoteAddr("   ");
    assertThat(controller.listSettlements(principal, PID, null, null, null, null, null).success())
        .isTrue();
    assertThat(
            controller
                .releaseSettlement(
                    principal, PID, SID, "idem-1", new ReleaseSettlementRequest(null))
                .success())
        .isTrue();
    assertThat(controller.releaseSettlement(principal, PID, SID, null, null).success()).isTrue();
    assertThat(
            controller
                .holdSettlement(principal, PID, SID, new HoldSettlementRequest("hold"))
                .success())
        .isTrue();
    assertThat(controller.holdSettlement(principal, PID, SID, null).success()).isTrue();
  }

  @Test
  void cashfree_payoutsWebhookControllerDelegates() {
    AdminPharmacySettlementService settlement = mock(AdminPharmacySettlementService.class);
    when(settlement.handlePayoutWebhook(any(), any())).thenReturn(Map.of("status", "PAID"));
    CashfreePayoutWebhookController controller = new CashfreePayoutWebhookController(settlement);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        com.nammamedmate.kernel.webhook.WebhookRawBodyFilter.CACHED_BODY_ATTR, "{}".getBytes());
    assertThat(controller.payoutWebhook(null, request).success()).isTrue();
  }
}
