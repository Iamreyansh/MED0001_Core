package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.SaasBillingService;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyBillingControllerTest {

  @Test
  void endpointsDelegate() {
    SaasBillingService billing = mock(SaasBillingService.class);
    PharmacyBillingController controller = new PharmacyBillingController(billing);
    UUID pharmacyId = Ids.newId();
    MedmatePrincipal owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "o");
    UUID id = Ids.newId();
    when(billing.listPharmacy(any(), any(), any()))
        .thenReturn(
            new SaasBillingService.PagedResult(
                Map.of("invoices", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(billing.getPharmacy(owner, id)).thenReturn(Map.of("id", id));
    when(billing.pay(eq(owner), any(), any(), any())).thenReturn(Map.of("checkout_url", "u"));

    assertThat(controller.list(owner, 1, 20).success()).isTrue();
    assertThat(controller.get(owner, id).data()).containsEntry("id", id);
    assertThat(
            controller
                .pay(owner, "idem", new PharmacyBillingController.PayRequest(id, "UPI"))
                .data())
        .containsKey("checkout_url");
    assertThat(controller.pay(owner, "idem", null).data()).containsKey("checkout_url");
  }
}
