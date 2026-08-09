package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.LeadPipelineService;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminCrmLeadControllerTest {

  @Mock LeadPipelineService leads;
  AdminCrmLeadController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminCrmLeadController(leads);
  }

  @Test
  void delegatesAllEndpoints() {
    UUID id = Ids.newId();
    UUID planId = Ids.newId();
    when(leads.list(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new LeadPipelineService.PagedResult(
                Map.of("leads", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(leads.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("id", id));
    when(leads.get(any(), eq(id))).thenReturn(Map.of("id", id));
    when(leads.update(
            any(), eq(id), any(), any(), any(), any(), eq(true), eq(true), eq(true), eq(true)))
        .thenReturn(Map.of("id", id));
    when(leads.advance(any(), eq(id), any())).thenReturn(Map.of("id", id));
    when(leads.markWon(any(), eq(id), any(), any())).thenReturn(Map.of("id", id));
    when(leads.markLost(any(), eq(id), any(), any())).thenReturn(Map.of("id", id));
    when(leads.reopen(any(), eq(id))).thenReturn(Map.of("id", id));

    assertThat(controller.list(principal, null, null, null, null, null, null).data())
        .containsKey("leads");
    assertThat(
            controller
                .create(
                    principal,
                    new AdminCrmLeadController.CreateLeadRequest(
                        "P",
                        "C",
                        "+91",
                        null,
                        "ORGANIC",
                        "STARTER",
                        new BigDecimal("699"),
                        null,
                        null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.create(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(controller.get(principal, id).data()).containsEntry("id", id);
    assertThat(
            controller
                .patch(
                    principal,
                    id,
                    new AdminCrmLeadController.UpdateLeadRequest(
                        Ids.newId(), new BigDecimal("1"), 10, "n"))
                .data())
        .containsEntry("id", id);
    when(leads.update(
            any(), eq(id), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), eq(false),
            eq(false)))
        .thenReturn(Map.of("id", id));
    assertThat(controller.patch(principal, id, null).data()).containsEntry("id", id);
    assertThat(
            controller
                .patch(
                    principal,
                    id,
                    new AdminCrmLeadController.UpdateLeadRequest(null, null, null, null))
                .data())
        .containsEntry("id", id);
    assertThat(
            controller.advance(principal, id, new AdminCrmLeadController.NotesRequest("n")).data())
        .containsEntry("id", id);
    assertThat(controller.advance(principal, id, null).data()).containsEntry("id", id);
    assertThat(
            controller
                .markWon(
                    principal, id, new AdminCrmLeadController.MarkWonRequest(planId, "MONTHLY"))
                .data())
        .containsEntry("id", id);
    assertThat(controller.markWon(principal, id, null).data()).containsEntry("id", id);
    assertThat(
            controller
                .markLost(principal, id, new AdminCrmLeadController.MarkLostRequest("PRICE", "n"))
                .data())
        .containsEntry("id", id);
    assertThat(controller.markLost(principal, id, null).data()).containsEntry("id", id);
    assertThat(controller.reopen(principal, id).data()).containsEntry("id", id);
    verify(leads).reopen(principal, id);
  }
}
