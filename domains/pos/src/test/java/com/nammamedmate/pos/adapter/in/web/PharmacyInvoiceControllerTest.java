package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.pos.application.InvoiceService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyInvoiceControllerTest {

  @Mock InvoiceService invoiceService;
  PharmacyInvoiceController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacyInvoiceController(invoiceService);
  }

  @Test
  void listJsonAndExport() {
    when(invoiceService.list(
            any(), any(), any(), any(), any(), any(), any(), any(), isNull(), isNull()))
        .thenReturn(
            new InvoiceService.ListResult(
                Map.of("invoices", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    Object json = controller.list(principal, null, null, null, null, null, 1, 20, null, null);
    assertThat(json).isInstanceOf(Map.class);

    when(invoiceService.list(
            any(), any(), any(), any(), any(), any(), any(), any(), eq("EXCEL"), isNull()))
        .thenReturn(
            new InvoiceService.FileExport(
                new byte[] {'P', 'K'},
                "invoices.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    Object file = controller.list(principal, null, null, null, null, null, 1, 20, "EXCEL", null);
    assertThat(file).isInstanceOf(ResponseEntity.class);

    assertThat(new InvoiceService.ListResult(null, PaginationMeta.of(1, 20, 0)).data()).isEmpty();
    assertThat(new InvoiceService.FileExport(null, "x.pdf", "application/pdf").bytes()).isEmpty();
  }

  @Test
  void detailPdfShareSettings() {
    UUID id = UUID.randomUUID();
    when(invoiceService.getDetail(principal, id)).thenReturn(Map.of("invoice_id", id.toString()));
    assertThat(controller.detail(principal, id).success()).isTrue();

    when(invoiceService.pdf(principal, id, "THERMAL"))
        .thenReturn(
            new InvoiceService.FileExport(new byte[] {'%', 'P'}, "inv.pdf", "application/pdf"));
    ResponseEntity<byte[]> pdf = controller.pdf(principal, id, "THERMAL");
    assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(pdf.getBody()[0]).isEqualTo((byte) '%');

    when(invoiceService.share(eq(principal), eq(id), eq("WHATSAPP"), eq("+91")))
        .thenReturn(Map.of("sent_at", "t"));
    ApiResponse<Map<String, Object>> share =
        controller.share(
            principal, id, new PharmacyInvoiceController.ShareRequest("WHATSAPP", "+91"));
    assertThat(share.data().get("sent_at")).isEqualTo("t");
    controller.share(principal, id, null);
    verify(invoiceService).share(principal, id, null, null);

    when(invoiceService.getSettings(principal)).thenReturn(Map.of("template", "MODERN"));
    assertThat(controller.getSettings(principal).data().get("template")).isEqualTo("MODERN");
    when(invoiceService.patchSettings(eq(principal), any()))
        .thenReturn(Map.of("invoice_prefix", "INV"));
    assertThat(controller.patchSettings(principal, Map.of()).data().get("invoice_prefix"))
        .isEqualTo("INV");
  }
}
