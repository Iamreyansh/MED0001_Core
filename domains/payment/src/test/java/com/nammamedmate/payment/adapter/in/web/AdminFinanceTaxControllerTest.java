package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.payment.application.TaxFacadeService;
import com.nammamedmate.payment.application.TaxFacadeService.PagedResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFinanceTaxControllerTest {

  @Mock private TaxFacadeService taxes;
  @InjectMocks private AdminFinanceTaxController controller;

  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @Test
  void panelFilingsGenerateMarkFiledAndRegister() {
    when(taxes.taxPanel(any(), any())).thenReturn(Map.of("month", "2026-07"));
    when(taxes.listFilings(any(), any(), any())).thenReturn(Map.of("filings", java.util.List.of()));
    when(taxes.generate(any(), any(), any())).thenReturn(Map.of("format", "JSON"));
    when(taxes.markFiled(any(), any(), any(), any(), any())).thenReturn(Map.of("status", "FILED"));
    when(taxes.tcsRegister(any(), any(), any(), any(), any()))
        .thenReturn(
            new PagedResult(Map.of("entries", java.util.List.of()), PaginationMeta.of(1, 50, 0)));

    ApiResponse<Map<String, Object>> panel = controller.panel(finance, "2026-07");
    assertThat(panel.data()).containsEntry("month", "2026-07");

    assertThat(controller.filings(finance, 2026, "PENDING").data()).containsKey("filings");

    UUID filingId = UUID.randomUUID();
    assertThat(
            controller
                .generate(finance, filingId, new AdminFinanceTaxController.GenerateRequest("CSV"))
                .data())
        .containsEntry("format", "JSON");
    controller.generate(finance, filingId, null);
    verify(taxes).generate(eq(finance), eq(filingId), eq("JSON"));

    assertThat(
            controller
                .markFiled(
                    finance,
                    filingId,
                    new AdminFinanceTaxController.MarkFiledRequest(
                        Instant.parse("2026-08-08T14:30:00Z"), "ARN-1", "notes"))
                .data())
        .containsEntry("status", "FILED");
    controller.markFiled(finance, filingId, null);
    verify(taxes).markFiled(eq(finance), eq(filingId), isNull(), isNull(), isNull());

    assertThat(controller.tcsRegister(finance, "2026-07", null, 1, 50).success()).isTrue();
  }
}
