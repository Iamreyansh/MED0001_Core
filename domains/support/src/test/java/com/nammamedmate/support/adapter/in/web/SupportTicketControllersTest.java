package com.nammamedmate.support.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.TicketService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SupportTicketControllersTest {

  @Mock TicketService tickets;
  @InjectMocks SupportTicketController support;
  @InjectMocks AdminSupportTicketController admin;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");

  @Test
  void supportEndpointsDelegate() {
    UUID id = UUID.randomUUID();
    when(tickets.create(any(), any())).thenReturn(Map.of("ticket_id", "TKT-1"));
    assertThat(support.create(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            support
                .create(
                    principal,
                    new SupportTicketController.CreateTicketRequest(
                        "ORDER", "s", "d", "APP", null, null, List.of(), "HIGH"))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    when(tickets.get(any(), eq(id))).thenReturn(Map.of("id", id));
    assertThat(support.get(principal, id).success()).isTrue();

    when(tickets.reply(any(), eq(id), any())).thenReturn(Map.of("message_id", UUID.randomUUID()));
    assertThat(support.reply(principal, id, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            support
                .reply(
                    principal,
                    id,
                    new SupportTicketController.ReplyRequest("hi", false, List.of(), null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    when(tickets.assign(any(), eq(id), isNull())).thenReturn(Map.of("assigned_to", id));
    assertThat(support.assign(principal, id, null).success()).isTrue();
    when(tickets.assign(any(), eq(id), eq(id))).thenReturn(Map.of("assigned_to", id));
    assertThat(
            support.assign(principal, id, new SupportTicketController.AssignRequest(id)).success())
        .isTrue();

    when(tickets.resolve(any(), eq(id), isNull())).thenReturn(Map.of("status", "RESOLVED"));
    assertThat(support.resolve(principal, id, null).success()).isTrue();
    when(tickets.resolve(any(), eq(id), eq("done"))).thenReturn(Map.of("status", "RESOLVED"));
    assertThat(
            support
                .resolve(principal, id, new SupportTicketController.ResolveRequest("done"))
                .success())
        .isTrue();

    when(tickets.reopen(any(), eq(id), isNull())).thenReturn(Map.of("status", "IN_PROGRESS"));
    assertThat(support.reopen(principal, id, null).success()).isTrue();
    when(tickets.reopen(any(), eq(id), eq("r"))).thenReturn(Map.of("status", "IN_PROGRESS"));
    assertThat(
            support.reopen(principal, id, new SupportTicketController.ReopenRequest("r")).success())
        .isTrue();

    when(tickets.escalate(any(), eq(id), isNull(), isNull()))
        .thenReturn(Map.of("supervisor_notified", true));
    assertThat(support.escalate(principal, id, null).success()).isTrue();
    when(tickets.escalate(any(), eq(id), eq("L3"), eq("r")))
        .thenReturn(Map.of("supervisor_notified", true));
    assertThat(
            support
                .escalate(principal, id, new SupportTicketController.EscalateRequest("L3", "r"))
                .success())
        .isTrue();

    when(tickets.changePriority(any(), eq(id), isNull())).thenReturn(Map.of("priority", "URGENT"));
    assertThat(support.changePriority(principal, id, null).success()).isTrue();
    when(tickets.changePriority(any(), eq(id), eq("URGENT")))
        .thenReturn(Map.of("priority", "URGENT"));
    assertThat(
            support
                .changePriority(
                    principal, id, new SupportTicketController.PriorityRequest("URGENT"))
                .success())
        .isTrue();
  }

  @Test
  void adminListAndCreate() {
    when(tickets.listAdmin(
            any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull()))
        .thenReturn(
            new TicketService.ListResult(
                Map.of("tickets", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(
            admin
                .list(principal, null, null, null, null, null, null, null, null, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    when(tickets.listAdmin(
            any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(false)))
        .thenReturn(new TicketService.ListResult(Map.of("tickets", java.util.List.of()), null));
    assertThat(
            admin
                .list(principal, null, null, null, null, null, null, null, null, false)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    when(tickets.listAdmin(
            any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(true)))
        .thenReturn(new TicketService.ListResult(Map.of("csv", "a,b\n"), null));
    when(tickets.exportCsvBytes(any())).thenReturn("a,b\n".getBytes());
    assertThat(
            admin
                .list(principal, null, null, null, null, null, null, null, null, true)
                .getHeaders()
                .getContentType()
                .toString())
        .contains("text/csv");

    when(tickets.createOnBehalf(any(), any())).thenReturn(Map.of("status", "OPEN"));
    assertThat(admin.createOnBehalf(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            admin
                .createOnBehalf(
                    principal,
                    new AdminSupportTicketController.AdminCreateRequest(
                        UUID.randomUUID(), "PAYMENT", "s", "d", null, "PHONE"))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }
}
