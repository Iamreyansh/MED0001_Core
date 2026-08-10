package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.prescription.adapter.in.web.AdminDrugRegisterController.ExportRequest;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService.ListResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminDrugRegisterControllerTest {

  @Test
  void delegates() {
    ScheduleDrugRegisterService service = mock(ScheduleDrugRegisterService.class);
    AdminDrugRegisterController controller = new AdminDrugRegisterController(service);
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    UUID pharmacy = UUID.randomUUID();
    UUID job = UUID.randomUUID();

    when(service.listAdmin(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ListResult(Map.of("entries", java.util.List.of()), PaginationMeta.of(1, 50, 0)));
    assertThat(controller.list(admin, "H1", pharmacy, null, null, null, 1, 50, false).success())
        .isTrue();

    when(service.retentionRules(admin)).thenReturn(Map.of("rules", java.util.List.of()));
    assertThat(controller.retentionRules(admin).data()).containsKey("rules");

    when(service.createExportJob(eq(admin), any(), any(), any(), any()))
        .thenReturn(Map.of("export_job_id", job, "status", "GENERATING"));
    assertThat(
            controller
                .export(admin, new ExportRequest(pharmacy, "H1", "2026-04-01", "2026-06-30"))
                .data()
                .get("status"))
        .isEqualTo("GENERATING");
    assertThat(controller.export(admin, null).data().get("status")).isEqualTo("GENERATING");

    when(service.pollExportJob(admin, job)).thenReturn(Map.of("status", "READY"));
    assertThat(controller.pollExport(admin, job).data().get("status")).isEqualTo("READY");
  }
}
