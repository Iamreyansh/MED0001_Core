package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.payment.application.FinanceOverviewService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFinanceOverviewControllerTest {

  @Mock private FinanceOverviewService overview;
  @InjectMocks private AdminFinanceOverviewController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    when(overview.kpi(principal)).thenReturn(Map.of("gmv_today", 1));
    when(overview.pnl(principal, "7D", null, null)).thenReturn(Map.of("period", "7D"));
    when(overview.cashPosition(principal, "30D", null, null)).thenReturn(Map.of("period", "30D"));
    when(overview.ratios(principal, "30D", "a", "b")).thenReturn(Map.of("take_rate_pct", 8));

    assertThat(controller.kpi(principal).data()).containsEntry("gmv_today", 1);
    assertThat(controller.pnl(principal, "7D", null, null).data()).containsEntry("period", "7D");
    assertThat(controller.cashPosition(principal, "30D", null, null).data())
        .containsEntry("period", "30D");
    assertThat(controller.ratios(principal, "30D", "a", "b").data())
        .containsEntry("take_rate_pct", 8);

    verify(overview).kpi(principal);
    verify(overview).pnl(principal, "7D", null, null);
    verify(overview).cashPosition(principal, "30D", null, null);
    verify(overview).ratios(principal, "30D", "a", "b");
  }
}
