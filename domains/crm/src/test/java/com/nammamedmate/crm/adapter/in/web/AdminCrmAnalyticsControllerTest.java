package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.SaasAnalyticsService;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCrmAnalyticsControllerTest {

  @Mock SaasAnalyticsService analytics;
  AdminCrmAnalyticsController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminCrmAnalyticsController(analytics);
  }

  @Test
  void delegatesAllEndpoints() {
    when(analytics.revenue(principal, "MONTH", null, null, null))
        .thenReturn(Map.of("period", "MONTH"));
    when(analytics.mrrBridge(principal, "2026-07")).thenReturn(Map.of("month", "2026-07"));
    when(analytics.cohort(principal, "2026-01", "2026-07"))
        .thenReturn(Map.of("cohort_retention", java.util.List.of()));
    when(analytics.unitEconomics(principal)).thenReturn(Map.of("arpa_rs", 1));
    when(analytics.report(principal, "MONTH", "2026-07", "PDF"))
        .thenReturn(Map.of("format", "PDF"));

    assertThat(controller.revenue(principal, "MONTH", null, null, null).data())
        .containsEntry("period", "MONTH");
    assertThat(controller.mrrBridge(principal, "2026-07").data()).containsEntry("month", "2026-07");
    assertThat(controller.cohort(principal, "2026-01", "2026-07").data())
        .containsKey("cohort_retention");
    assertThat(controller.unitEconomics(principal).data()).containsKey("arpa_rs");
    assertThat(controller.report(principal, "MONTH", "2026-07", "PDF").data())
        .containsEntry("format", "PDF");

    verify(analytics).revenue(eq(principal), eq("MONTH"), isNull(), isNull(), isNull());
  }
}
