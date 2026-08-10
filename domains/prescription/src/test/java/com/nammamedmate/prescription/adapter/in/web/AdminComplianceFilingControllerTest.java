package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.prescription.adapter.in.web.AdminComplianceFilingController.DrugRecallRequest;
import com.nammamedmate.prescription.adapter.in.web.AdminComplianceFilingController.GenerateRequest;
import com.nammamedmate.prescription.adapter.in.web.AdminComplianceFilingController.MarkFiledRequest;
import com.nammamedmate.prescription.application.ComplianceFilingService;
import com.nammamedmate.prescription.application.ComplianceFilingService.ActivityResult;
import com.nammamedmate.prescription.application.ComplianceFilingService.ListResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminComplianceFilingControllerTest {

  @Test
  void delegates() {
    ComplianceFilingService service = mock(ComplianceFilingService.class);
    AdminComplianceFilingController controller = new AdminComplianceFilingController(service);
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    UUID filing = UUID.randomUUID();
    UUID job = UUID.randomUUID();

    when(service.listFilings(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new ListResult(Map.of("filings", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(controller.listFilings(admin, null, null, 2026, false, 1, 20).success()).isTrue();

    when(service.startGenerate(eq(admin), eq(filing), any(), any()))
        .thenReturn(Map.of("job_id", job, "status", "GENERATING"));
    assertThat(
            controller
                .generate(admin, filing, new GenerateRequest("2026-06", "CSV"))
                .data()
                .get("status"))
        .isEqualTo("GENERATING");
    assertThat(controller.generate(admin, filing, null).data().get("status"))
        .isEqualTo("GENERATING");

    when(service.pollGenerate(admin, filing, job)).thenReturn(Map.of("status", "READY"));
    assertThat(controller.pollGenerate(admin, filing, job).data().get("status")).isEqualTo("READY");

    when(service.markFiled(any(), eq(filing), any(), any(), any()))
        .thenReturn(Map.of("status", "FILED"));
    assertThat(
            controller
                .markFiled(
                    admin,
                    filing,
                    new MarkFiledRequest(
                        admin.subject(), Instant.parse("2026-07-12T14:30:00Z"), "R"))
                .data()
                .get("status"))
        .isEqualTo("FILED");
    assertThat(controller.markFiled(admin, filing, null).data().get("status")).isEqualTo("FILED");

    when(service.listActivity(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new ActivityResult(List.of(), PaginationMeta.of(1, 50, 0)));
    assertThat(controller.activityLog(admin, null, null, null, null, 1, 50).success()).isTrue();

    when(service.initiateDrugRecall(any(), any(), any(), any()))
        .thenReturn(Map.of("batches_banned", 1));
    assertThat(
            controller
                .drugRecall(admin, new DrugRecallRequest("Paracetamol 500mg", "PCM2024Q1", "x"))
                .data()
                .get("batches_banned"))
        .isEqualTo(1);
    assertThat(controller.drugRecall(admin, null).data().get("batches_banned")).isEqualTo(1);
  }
}
