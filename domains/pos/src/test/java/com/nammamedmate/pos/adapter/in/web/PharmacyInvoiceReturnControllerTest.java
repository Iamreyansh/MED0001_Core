package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.pos.application.PosInsuranceClaimService;
import com.nammamedmate.pos.application.PosReturnService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PharmacyInvoiceReturnControllerTest {

  @Test
  void delegatesReturnAndClaim() {
    PosReturnService returns = mock(PosReturnService.class);
    PosInsuranceClaimService claims = mock(PosInsuranceClaimService.class);
    PharmacyInvoiceReturnController controller =
        new PharmacyInvoiceReturnController(returns, claims);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
    UUID invoiceId = UUID.randomUUID();
    when(returns.createReturn(owner, invoiceId, "r", null)).thenReturn(Map.of("ok", true));
    assertThat(controller.createReturn(owner, invoiceId, null).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    when(returns.createReturn(owner, invoiceId, "broken", List.of()))
        .thenReturn(Map.of("credit_note_id", "1"));
    assertThat(
            controller
                .createReturn(
                    owner,
                    invoiceId,
                    new PharmacyInvoiceReturnController.ReturnRequest("broken", List.of()))
                .getBody()
                .data()
                .get("credit_note_id"))
        .isEqualTo("1");
    when(claims.submit(owner, invoiceId, null, null, null))
        .thenReturn(Map.of("status", "SUBMITTED"));
    assertThat(controller.submitClaim(owner, invoiceId, null).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    when(claims.submit(owner, invoiceId, "Star", "P1", "n"))
        .thenReturn(Map.of("status", "SUBMITTED"));
    assertThat(
            controller
                .submitClaim(
                    owner,
                    invoiceId,
                    new PharmacyInvoiceReturnController.ClaimRequest("Star", "P1", "n"))
                .getBody()
                .success())
        .isTrue();
    when(claims.get(owner, invoiceId)).thenReturn(Map.of("status", "PENDING"));
    assertThat(controller.getClaim(owner, invoiceId).data().get("status")).isEqualTo("PENDING");
  }
}
