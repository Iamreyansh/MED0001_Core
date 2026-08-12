package com.nammamedmate.observability_ops.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.observability_ops.application.IncidentService;
import com.nammamedmate.observability_ops.application.IncidentService.IncidentsPage;
import com.nammamedmate.observability_ops.application.MonitoringQueryService;
import com.nammamedmate.observability_ops.application.MonitoringQueryService.AlertsPage;
import com.nammamedmate.observability_ops.application.RemediationService;
import com.nammamedmate.observability_ops.application.RemediationService.ActionsPage;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminMonitoringControllerTest {

  @Mock MonitoringQueryService monitoring;
  @Mock RemediationService remediation;
  @Mock IncidentService incidents;
  @InjectMocks AdminMonitoringController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    when(monitoring.realtime(principal)).thenReturn(Map.of("ok", true));
    when(monitoring.alerts(principal, "ACTIVE", null, 1))
        .thenReturn(
            new AlertsPage(Map.of("alerts", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(monitoring.acknowledge(
            principal, UUID.fromString("11111111-1111-1111-1111-111111111111"), "n"))
        .thenReturn(Map.of("acknowledged", true));
    when(monitoring.metrics(principal, "gmv", 60)).thenReturn(Map.of("metric_name", "gmv"));
    when(monitoring.slo(principal)).thenReturn(Map.of("slos", java.util.List.of()));
    when(remediation.listActions(principal, null, null, null, null, 1))
        .thenReturn(
            new ActionsPage(
                Map.of("remediation_actions", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(remediation.listPlaybooks(principal)).thenReturn(Map.of("playbooks", java.util.List.of()));
    UUID pb = UUID.fromString("02000002-0001-4000-8000-000000000001");
    when(remediation.patchPlaybook(principal, pb, false, null))
        .thenReturn(Map.of("playbook_id", pb));
    when(remediation.patchPlaybook(principal, pb, null, null))
        .thenReturn(Map.of("playbook_id", pb));
    when(remediation.patchPlaybook(eq(principal), eq(pb), eq(true), any()))
        .thenReturn(Map.of("playbook_id", pb));
    when(remediation.triggerManual(principal, "REQUEST_RIDERS", "ZONE", pb, "r"))
        .thenReturn(Map.of("status", "INITIATED"));
    when(remediation.triggerManual(principal, null, null, null, null))
        .thenReturn(Map.of("status", "INITIATED"));
    when(incidents.list(principal, null, null, null, null, 1))
        .thenReturn(new IncidentsPage(Map.of("incidents", List.of()), PaginationMeta.of(1, 20, 0)));
    when(incidents.declare(principal, "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of("k", 1)))
        .thenReturn(Map.of("id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    when(incidents.declare(principal, null, null, null, null, null))
        .thenReturn(Map.of("id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    UUID iid = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    when(incidents.patchStatus(principal, iid, "INVESTIGATING", "looking"))
        .thenReturn(Map.of("new_status", "INVESTIGATING"));
    when(incidents.patchStatus(principal, iid, null, null))
        .thenReturn(Map.of("new_status", "INVESTIGATING"));
    when(incidents.resolve(principal, iid, "root", "fix", "prevent"))
        .thenReturn(Map.of("status", "RESOLVED"));
    when(incidents.resolve(principal, iid, null, null, null))
        .thenReturn(Map.of("status", "RESOLVED"));
    when(incidents.filePostmortem(principal, iid)).thenReturn(Map.of("postmortem_filed", true));
    when(incidents.sloHistory(principal, "payment_success", "2026-07-01", "2026-07-31"))
        .thenReturn(Map.of("history", List.of()));
    when(incidents.sloHistory(principal, null, null, null))
        .thenReturn(Map.of("history", List.of()));

    assertThat(controller.realtime(principal).data()).containsEntry("ok", true);
    assertThat(controller.alerts(principal, "ACTIVE", null, 1).data()).containsKey("alerts");
    assertThat(
            controller
                .acknowledge(
                    principal,
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    new AdminMonitoringController.AcknowledgeRequest("n"))
                .data())
        .containsEntry("acknowledged", true);
    assertThat(controller.acknowledge(principal, UUID.randomUUID(), null).success()).isTrue();
    assertThat(controller.metrics(principal, "gmv", 60).data()).containsEntry("metric_name", "gmv");
    assertThat(controller.slo(principal).data()).containsKey("slos");
    assertThat(controller.remediationActions(principal, null, null, null, null, 1).data())
        .containsKey("remediation_actions");
    assertThat(controller.remediationPlaybooks(principal).data()).containsKey("playbooks");
    assertThat(
            controller
                .patchPlaybook(
                    principal, pb, new AdminMonitoringController.PatchPlaybookRequest(false, null))
                .data())
        .containsKey("playbook_id");
    assertThat(
            controller
                .patchPlaybook(
                    principal,
                    pb,
                    new AdminMonitoringController.PatchPlaybookRequest(
                        true, Map.of("dark_duration_minutes", 45)))
                .data())
        .containsKey("playbook_id");
    assertThat(
            controller
                .triggerRemediation(
                    principal,
                    new AdminMonitoringController.TriggerRemediationRequest(
                        "REQUEST_RIDERS", "ZONE", pb, "r"))
                .data())
        .containsEntry("status", "INITIATED");
    assertThat(controller.triggerRemediation(principal, null).success()).isTrue();
    assertThat(controller.patchPlaybook(principal, pb, null).success()).isTrue();

    assertThat(controller.listIncidents(principal, null, null, null, null, 1).data())
        .containsKey("incidents");
    assertThat(
            controller
                .createIncident(
                    principal,
                    new AdminMonitoringController.CreateIncidentRequest(
                        "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of("k", 1)))
                .data())
        .containsKey("id");
    assertThat(
            new AdminMonitoringController.CreateIncidentRequest("t", "P1", "d", null, null)
                .affectedServices())
        .isNull();
    assertThat(controller.createIncident(principal, null).data()).containsKey("id");
    assertThat(
            controller
                .patchIncident(
                    principal,
                    iid,
                    new AdminMonitoringController.PatchIncidentRequest("INVESTIGATING", "looking"))
                .data())
        .containsEntry("new_status", "INVESTIGATING");
    assertThat(controller.patchIncident(principal, iid, null).success()).isTrue();
    assertThat(
            controller
                .resolveIncident(
                    principal,
                    iid,
                    new AdminMonitoringController.ResolveIncidentRequest("root", "fix", "prevent"))
                .data())
        .containsEntry("status", "RESOLVED");
    assertThat(controller.resolveIncident(principal, iid, null).success()).isTrue();
    assertThat(controller.filePostmortem(principal, iid).data())
        .containsEntry("postmortem_filed", true);
    assertThat(
            controller.sloHistory(principal, "payment_success", "2026-07-01", "2026-07-31").data())
        .containsKey("history");
    assertThat(controller.sloHistory(principal, null, null, null).data()).containsKey("history");
    verify(monitoring).realtime(principal);
    verify(incidents).filePostmortem(principal, iid);
  }
}
