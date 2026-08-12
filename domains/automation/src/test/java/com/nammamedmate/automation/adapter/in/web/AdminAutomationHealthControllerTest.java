package com.nammamedmate.automation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.AutomationHealthService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAutomationHealthControllerTest {

  @Mock AutomationHealthService health;
  @InjectMocks AdminAutomationHealthController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegates() {
    when(health.dashboard(any())).thenReturn(Map.of("kill_switch_status", "ACTIVE"));
    when(health.perRule(any())).thenReturn(Map.of("rules", java.util.List.of()));
    when(health.circuitBreakers(any())).thenReturn(Map.of("circuit_breakers", java.util.List.of()));
    when(health.toggle(any(), any(), any())).thenReturn(Map.of("action", "PAUSE"));

    assertThat(controller.dashboard(principal).data())
        .containsEntry("kill_switch_status", "ACTIVE");
    assertThat(controller.perRule(principal).success()).isTrue();
    assertThat(controller.circuitBreakers(principal).success()).isTrue();
    assertThat(controller.killSwitch(principal, null).data()).containsEntry("action", "PAUSE");
    verify(health).toggle(principal, null, null);
    assertThat(
            controller
                .killSwitch(
                    principal,
                    new AdminAutomationHealthController.KillSwitchRequest("PAUSE", "reason"))
                .success())
        .isTrue();
    verify(health).toggle(eq(principal), eq("PAUSE"), eq("reason"));
  }
}
