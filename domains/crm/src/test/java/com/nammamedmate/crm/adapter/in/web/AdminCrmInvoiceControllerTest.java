package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.SaasBillingService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminCrmInvoiceControllerTest {

  @Test
  void endpointsDelegate() {
    SaasBillingService billing = mock(SaasBillingService.class);
    AdminCrmInvoiceController controller = new AdminCrmInvoiceController(billing);
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    UUID id = Ids.newId();
    when(billing.listAdmin(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new SaasBillingService.PagedResult(
                Map.of("invoices", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(billing.getAdmin(admin, id)).thenReturn(Map.of("id", id));
    when(billing.sendReminder(admin, id)).thenReturn(Map.of("invoice_id", id));
    when(billing.markPaid(eq(admin), eq(id), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "PAID"));

    ApiResponse<Map<String, Object>> list =
        controller.list(admin, "DUE", "STARTER", id, LocalDate.now(), LocalDate.now(), 1, 20);
    assertThat(list.success()).isTrue();
    assertThat(controller.get(admin, id).data()).containsEntry("id", id);
    assertThat(controller.sendReminder(admin, id).data()).containsKey("invoice_id");
    assertThat(
            controller
                .markPaid(
                    admin,
                    id,
                    "idem",
                    new AdminCrmInvoiceController.MarkPaidRequest(LocalDate.now(), "NEFT", "r"))
                .data())
        .containsEntry("status", "PAID");
    assertThat(controller.markPaid(admin, id, "idem", null).data()).containsEntry("status", "PAID");
  }
}
