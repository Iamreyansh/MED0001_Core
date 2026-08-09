package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.pos.application.InvoiceService;
import com.nammamedmate.pos.application.SalesLedgerService;
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
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacySalesControllerTest {

  @Mock SalesLedgerService salesLedgerService;
  PharmacySalesController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacySalesController(salesLedgerService);
  }

  @Test
  void listJsonAndExport() {
    when(salesLedgerService.list(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), isNull(),
            any(), any()))
        .thenReturn(
            new SalesLedgerService.ListResult(
                Map.of("sales", java.util.List.of(), "period_summary", Map.of()),
                PaginationMeta.of(1, 20, 0)));
    Object json =
        controller.list(
            principal, null, null, null, null, null, null, null, null, 1, 20, null, null, null);
    assertThat(json).isInstanceOf(Map.class);

    when(salesLedgerService.list(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq("EXCEL"),
            any(),
            any()))
        .thenReturn(
            new InvoiceService.FileExport(
                new byte[] {'P', 'K'},
                "sales.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    Object file =
        controller.list(
            principal, null, null, null, null, null, null, null, null, 1, 20, "EXCEL", null, null);
    assertThat(file).isInstanceOf(ResponseEntity.class);
  }

  @Test
  void summaryDetailMarkPaid() {
    UUID id = UUID.randomUUID();
    when(salesLedgerService.summary(eq(principal), any(), any(), isNull()))
        .thenReturn(Map.of("total_bills", 1L));
    assertThat(controller.summary(principal, null, null, null).data().get("total_bills"))
        .isEqualTo(1L);

    when(salesLedgerService.getDetail(principal, id))
        .thenReturn(Map.of("invoice_id", id.toString()));
    assertThat(controller.detail(principal, id).success()).isTrue();

    when(salesLedgerService.markPaid(eq(principal), eq(id), any()))
        .thenReturn(Map.of("receipt_number", "RCPT-1"));
    ApiResponse<Map<String, Object>> paid =
        controller.markPaid(principal, id, Map.of("payment_mode", "CASH", "amount", 10));
    assertThat(paid.data().get("receipt_number")).isEqualTo("RCPT-1");
  }
}
