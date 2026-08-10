package com.nammamedmate.support.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.AgentService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminSupportAgentControllerTest {

  @Mock AgentService agents;
  @InjectMocks AdminSupportAgentController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void endpointsDelegate() {
    when(agents.listAgents(any())).thenReturn(Map.of("total_agents", 0));
    assertThat(controller.list(principal).getStatusCode()).isEqualTo(HttpStatus.OK);

    UUID id = UUID.randomUUID();
    when(agents.getDetail(any(), eq(id))).thenReturn(Map.of("id", id));
    assertThat(controller.detail(principal, id).getStatusCode()).isEqualTo(HttpStatus.OK);

    when(agents.getWorkload(any(), eq(id))).thenReturn(Map.of("agent_id", id));
    assertThat(controller.workload(principal, id).getStatusCode()).isEqualTo(HttpStatus.OK);

    when(agents.toggleStatus(any(), eq(id), any())).thenReturn(Map.of("is_online", true));
    assertThat(controller.status(principal, id, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            controller
                .status(principal, id, new AdminSupportAgentController.StatusRequest(true))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    UUID ticketId = UUID.randomUUID();
    when(agents.suggestAssignment(any(), eq(ticketId))).thenReturn(Map.of("overflow", false));
    assertThat(controller.suggest(principal, ticketId).getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
