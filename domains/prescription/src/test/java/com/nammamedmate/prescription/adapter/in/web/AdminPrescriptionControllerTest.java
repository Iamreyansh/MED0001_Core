package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.prescription.adapter.in.web.AdminPrescriptionController.FlagRequest;
import com.nammamedmate.prescription.adapter.in.web.AdminPrescriptionController.VerifyRequest;
import com.nammamedmate.prescription.application.RxComplianceAuditService;
import com.nammamedmate.prescription.application.RxComplianceAuditService.ListResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminPrescriptionControllerTest {

  @Test
  void delegates() {
    RxComplianceAuditService service = mock(RxComplianceAuditService.class);
    AdminPrescriptionController controller = new AdminPrescriptionController(service);
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    UUID rx = UUID.randomUUID();

    when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ListResult(
                Map.of("prescriptions", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(
            controller
                .list(admin, null, null, null, null, null, null, null, 1, 20, false)
                .success())
        .isTrue();

    when(service.get(admin, rx)).thenReturn(Map.of("rx_id", rx));
    assertThat(controller.get(admin, rx).data().get("rx_id")).isEqualTo(rx);

    when(service.verify(eq(admin), eq(rx), any(), any(), any()))
        .thenReturn(Map.of("audit_status", "VERIFIED"));
    assertThat(
            controller
                .verify(admin, rx, new VerifyRequest(true, null, "ok"))
                .data()
                .get("audit_status"))
        .isEqualTo("VERIFIED");
    assertThat(controller.verify(admin, rx, null).data().get("audit_status")).isEqualTo("VERIFIED");

    when(service.flag(eq(admin), eq(rx), any(), any()))
        .thenReturn(Map.of("audit_status", "FLAGGED"));
    assertThat(controller.flag(admin, rx, new FlagRequest("r", "HIGH")).data().get("audit_status"))
        .isEqualTo("FLAGGED");
    assertThat(controller.flag(admin, rx, null).data().get("audit_status")).isEqualTo("FLAGGED");

    when(service.statistics(admin, null, null)).thenReturn(Map.of("total_audited", 1));
    assertThat(controller.statistics(admin, null, null).data().get("total_audited")).isEqualTo(1);

    assertThat(new ListResult(null, PaginationMeta.of(1, 1, 0)).data()).isEmpty();
  }
}
