package com.nammamedmate.support.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.SlaService;
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
class AdminSupportSlaControllerTest {

  @Mock SlaService sla;
  @InjectMocks AdminSupportSlaController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void endpointsDelegate() {
    when(sla.listPolicies(any())).thenReturn(Map.of("sla_policies", List.of()));
    assertThat(controller.listPolicies(principal).getStatusCode()).isEqualTo(HttpStatus.OK);

    UUID id = UUID.randomUUID();
    when(sla.updatePolicy(any(), eq(id), isNull(), isNull())).thenReturn(Map.of("id", id));
    assertThat(controller.updatePolicy(principal, id, null).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    when(sla.updatePolicy(any(), eq(id), eq(45), eq(960))).thenReturn(Map.of("id", id));
    assertThat(
            controller
                .updatePolicy(
                    principal, id, new AdminSupportSlaController.UpdatePolicyRequest(45, 960))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    when(sla.listBreaches(any(), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("breach_count", 0, "breaches", List.of()));
    assertThat(controller.listBreaches(principal, null, null, null).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    when(sla.getEscalationMatrix(any())).thenReturn(Map.of("escalation_matrix", List.of()));
    assertThat(controller.getMatrix(principal).getStatusCode()).isEqualTo(HttpStatus.OK);

    when(sla.updateEscalationMatrix(any(), any()))
        .thenReturn(Map.of("updated_levels", List.of("L2")));
    assertThat(controller.updateMatrix(principal, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            controller
                .updateMatrix(principal, new AdminSupportSlaController.UpdateMatrixRequest(null))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            controller
                .updateMatrix(
                    principal,
                    new AdminSupportSlaController.UpdateMatrixRequest(
                        java.util.Arrays.asList(
                            new AdminSupportSlaController.MatrixRulePatch(
                                "L2", 90, List.of("IN_APP", "EMAIL")),
                            new AdminSupportSlaController.MatrixRulePatch(null, 1, List.of()),
                            new AdminSupportSlaController.MatrixRulePatch("  ", 1, null),
                            null)))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }
}
