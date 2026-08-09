package com.nammamedmate.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.payment.application.LedgerFacadeService;
import com.nammamedmate.payment.application.LedgerFacadeService.PagedResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AdminFinanceLedgerControllerTest {

  @Mock private LedgerFacadeService ledger;
  @InjectMocks private AdminFinanceLedgerController controller;

  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @Test
  void browseExportAndCsv() throws Exception {
    when(ledger.browse(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PagedResult(Map.of("entries", java.util.List.of()), PaginationMeta.of(1, 50, 0)));
    when(ledger.export(any(), any(), any(), any()))
        .thenReturn(Map.of("download_url", "https://s3/x.csv", "record_count", 0));
    when(ledger.browseCsv(any(), any(), any(), any()))
        .thenReturn("ledger_id\n".getBytes(StandardCharsets.UTF_8));

    ApiResponse<Map<String, Object>> browse =
        controller.browse(
            finance, "ORDER_GMV", "2026-07-01", "2026-07-24", 1, 50, "created_at", "desc");
    assertThat(browse.success()).isTrue();
    assertThat(browse.data()).containsKey("entries");

    assertThat(controller.export(finance, "2026-07-01", "2026-07-31", null).data())
        .containsEntry("download_url", "https://s3/x.csv");
    verify(ledger).export(eq(finance), eq("2026-07-01"), eq("2026-07-31"), isNull());

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.browseCsv(finance, null, null, null, response);
    assertThat(response.getContentType()).contains("text/csv");
    assertThat(response.getContentAsString()).contains("ledger_id");
  }
}
