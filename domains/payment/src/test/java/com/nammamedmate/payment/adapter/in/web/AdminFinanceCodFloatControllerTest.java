package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.payment.application.CodFloatFacadeService;
import com.nammamedmate.payment.application.CodFloatFacadeService.PagedResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AdminFinanceCodFloatControllerTest {

  @Mock private CodFloatFacadeService floats;
  @InjectMocks private AdminFinanceCodFloatController controller;

  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @Test
  void summaryReportAutoReconcileAndExport() throws Exception {
    when(floats.floatSummary(any(), any(), any(), any(), any()))
        .thenReturn(new PagedResult(Map.of("summary", Map.of()), PaginationMeta.of(1, 20, 0)));
    when(floats.reconciliationReport(any(), any()))
        .thenReturn(Map.of("reconciliation_status", "BALANCED"));
    when(floats.autoReconcile(any(), any())).thenReturn(Map.of("status", "RUNNING"));

    ApiResponse<Map<String, Object>> summary = controller.summary(finance, null, true, 1, 20);
    assertThat(summary.success()).isTrue();

    assertThat(controller.reconciliationReport(finance, LocalDate.parse("2026-07-24")).data())
        .containsEntry("reconciliation_status", "BALANCED");

    assertThat(
            controller
                .autoReconcile(
                    finance,
                    new AdminFinanceCodFloatController.AutoReconcileRequest(
                        LocalDate.parse("2026-07-24")))
                .data())
        .containsEntry("status", "RUNNING");

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.export(finance, LocalDate.parse("2026-07-24"), response);
    verify(floats).exportReconciliationCsv(eq(finance), eq(LocalDate.parse("2026-07-24")), any());
    assertThat(response.getContentType()).contains("text/csv");

    MockHttpServletResponse response2 = new MockHttpServletResponse();
    controller.export(finance, null, response2);
    verify(floats).exportReconciliationCsv(eq(finance), isNull(), any());
    assertThat(response2.getHeader(HttpHeaders.CONTENT_DISPOSITION))
        .contains("cod-reconciliation-");

    controller.autoReconcile(finance, null);
    verify(floats).autoReconcile(eq(finance), isNull());
  }
}
