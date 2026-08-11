package com.nammamedmate.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.PharmacyAnalyticsService;
import com.nammamedmate.analytics.application.PharmacyAnalyticsService.PageResult;
import com.nammamedmate.kernel.api.PaginationMeta;
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
class AdminPharmacyAnalyticsControllerTest {

  @Mock PharmacyAnalyticsService service;
  @InjectMocks AdminPharmacyAnalyticsController controller;

  private final UUID pharmacyId = UUID.randomUUID();
  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesAdminMirrorEndpoints() {
    when(service.overview(principal, pharmacyId, "7D", null, null)).thenReturn(Map.of("ok", true));
    when(service.salesRegister(principal, pharmacyId, "7D", null, null, null, null, 1, 20))
        .thenReturn(
            new PageResult(Map.of("sales", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(service.products(principal, pharmacyId, "7D", null, null, null, null, null, 1, 20))
        .thenReturn(
            new PageResult(Map.of("products", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(service.accountsGst(principal, pharmacyId, "7D", null, null))
        .thenReturn(Map.of("pl_card", Map.of()));
    when(service.reportsCatalogue(principal, pharmacyId))
        .thenReturn(Map.of("reports", java.util.List.of()));
    when(service.runReport(principal, pharmacyId, "DAYBOOK", "7D", null, null, "pdf"))
        .thenReturn(Map.of("report_id", "DAYBOOK"));

    assertThat(controller.overview(principal, pharmacyId, "7D", null, null).data())
        .containsEntry("ok", true);
    assertThat(
            controller
                .salesRegister(principal, pharmacyId, "7D", null, null, null, null, 1, 20)
                .data())
        .containsKey("sales");
    assertThat(
            controller
                .products(principal, pharmacyId, "7D", null, null, null, null, null, 1, 20)
                .data())
        .containsKey("products");
    assertThat(controller.accountsGst(principal, pharmacyId, "7D", null, null).data())
        .containsKey("pl_card");
    assertThat(controller.catalogue(principal, pharmacyId).data()).containsKey("reports");
    assertThat(controller.report(principal, pharmacyId, "DAYBOOK", "7D", null, null, "pdf").data())
        .containsEntry("report_id", "DAYBOOK");
  }
}
