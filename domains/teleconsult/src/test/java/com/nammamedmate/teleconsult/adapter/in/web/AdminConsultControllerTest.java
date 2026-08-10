package com.nammamedmate.teleconsult.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.adapter.in.web.AdminConsultController.EprescriptionBody;
import com.nammamedmate.teleconsult.adapter.in.web.AdminConsultController.StatusBody;
import com.nammamedmate.teleconsult.application.ConsultEPrescriptionService;
import com.nammamedmate.teleconsult.application.ConsultEPrescriptionService.IssueRequest;
import com.nammamedmate.teleconsult.application.ConsultSessionService;
import com.nammamedmate.teleconsult.application.ConsultSessionService.AdminListResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdminConsultControllerTest {

  @Test
  void delegatesIncludingNullBody() {
    ConsultSessionService service = mock(ConsultSessionService.class);
    ConsultEPrescriptionService eRx = mock(ConsultEPrescriptionService.class);
    AdminConsultController controller = new AdminConsultController(service, eRx);
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    UUID id = UUID.randomUUID();

    when(service.updateStatus(eq(admin), eq(id), eq("IN_CALL"), eq("n"), eq(true), eq("c")))
        .thenReturn(Map.of("status", "IN_CALL"));
    assertThat(
            controller
                .updateStatus(admin, id, new StatusBody("IN_CALL", "n", true, "c"))
                .data()
                .get("status"))
        .isEqualTo("IN_CALL");
    when(service.updateStatus(eq(admin), eq(id), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("status", "REQUESTED"));
    assertThat(controller.updateStatus(admin, id, null).data().get("status"))
        .isEqualTo("REQUESTED");

    when(service.queue(admin)).thenReturn(Map.of("pending_list", java.util.List.of()));
    assertThat(controller.queue(admin).success()).isTrue();

    when(service.list(eq(admin), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new AdminListResult(
                Map.of("consults", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(controller.list(admin, null, null, null, null, null, null).success()).isTrue();

    when(eRx.issue(eq(admin), eq(id), any(IssueRequest.class))).thenReturn(Map.of("rx_id", "RX-1"));
    assertThat(
            controller
                .issueEprescription(admin, id, new EprescriptionBody(null, true, "advice", null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.issueEprescription(admin, id, null).getBody().data().get("rx_id"))
        .isEqualTo("RX-1");
  }
}
