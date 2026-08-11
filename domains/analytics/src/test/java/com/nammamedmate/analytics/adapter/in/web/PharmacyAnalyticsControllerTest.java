package com.nammamedmate.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
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
class PharmacyAnalyticsControllerTest {

  @Mock PharmacyAnalyticsService service;
  @InjectMocks PharmacyAnalyticsController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    when(service.overview(principal, null, "30D", null, null)).thenReturn(Map.of("period", "30D"));
    when(service.salesRegister(principal, null, "30D", null, null, null, null, null, null))
        .thenReturn(
            new PageResult(Map.of("sales", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(service.products(principal, null, "30D", null, null, null, null, null, null, null))
        .thenReturn(
            new PageResult(Map.of("products", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(service.accountsGst(principal, null, "30D", null, null))
        .thenReturn(Map.of("pl_card", Map.of()));
    when(service.reportsCatalogue(principal, null))
        .thenReturn(Map.of("reports", java.util.List.of()));
    when(service.runReport(principal, null, "DAYBOOK", "30D", null, null, null))
        .thenReturn(Map.of("report_id", "DAYBOOK"));
    when(service.setFavorite(principal, null, "DAYBOOK", true))
        .thenReturn(Map.of("is_favorite", true));
    when(service.setFavorite(principal, null, "DAYBOOK", null))
        .thenReturn(Map.of("is_favorite", false));

    assertThat(controller.overview(principal, "30D", null, null).data())
        .containsEntry("period", "30D");
    assertThat(
            controller.salesRegister(principal, "30D", null, null, null, null, null, null).data())
        .containsKey("sales");
    assertThat(
            controller.products(principal, "30D", null, null, null, null, null, null, null).data())
        .containsKey("products");
    assertThat(controller.accountsGst(principal, "30D", null, null).data()).containsKey("pl_card");
    assertThat(controller.catalogue(principal).data()).containsKey("reports");
    assertThat(controller.report(principal, "DAYBOOK", "30D", null, null, null).data())
        .containsEntry("report_id", "DAYBOOK");
    assertThat(controller.favorite(principal, "DAYBOOK", Map.of("is_favorite", true)).data())
        .containsEntry("is_favorite", true);
    assertThat(controller.favorite(principal, "DAYBOOK", null).data())
        .containsEntry("is_favorite", false);
    assertThat(controller.favorite(principal, "DAYBOOK", Map.of()).data())
        .containsEntry("is_favorite", false);

    verify(service).setFavorite(principal, null, "DAYBOOK", true);
  }
}
