package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.FeatureAdoptionService;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCrmModuleControllerTest {

  @Mock FeatureAdoptionService adoption;
  AdminCrmModuleController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminCrmModuleController(adoption);
  }

  @Test
  void delegatesAllEndpoints() {
    UUID accountId = Ids.newId();
    when(adoption.listModules(any(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("modules", java.util.List.of()));
    when(adoption.getModule(any(), eq("mod_billing")))
        .thenReturn(Map.of("module_id", "mod_billing"));
    when(adoption.toggleModule(any(), eq(accountId), eq("mod_billing"), eq(true), eq("beta")))
        .thenReturn(Map.of("override", true));
    when(adoption.usageSummary(any(), eq(accountId))).thenReturn(Map.of("account_id", accountId));
    when(adoption.nudgeIneligible(any(), eq("mod_billing"), eq("EMAIL")))
        .thenReturn(Map.of("nudge_sent_count", 1));

    assertThat(controller.listModules(principal, null, null, null, null).data())
        .containsKey("modules");
    assertThat(controller.getModule(principal, "mod_billing").data())
        .containsEntry("module_id", "mod_billing");
    assertThat(
            controller
                .toggle(
                    principal,
                    accountId,
                    "mod_billing",
                    new AdminCrmModuleController.ToggleRequest(true, "beta"))
                .data())
        .containsEntry("override", true);
    assertThat(controller.usageSummary(principal, accountId).data())
        .containsEntry("account_id", accountId);
    assertThat(
            controller
                .nudge(principal, new AdminCrmModuleController.NudgeRequest("mod_billing", "EMAIL"))
                .data())
        .containsEntry("nudge_sent_count", 1);

    when(adoption.toggleModule(any(), eq(accountId), eq("mod_billing"), isNull(), isNull()))
        .thenReturn(Map.of("override", true));
    when(adoption.nudgeIneligible(any(), isNull(), isNull()))
        .thenReturn(Map.of("nudge_sent_count", 0));
    assertThat(controller.toggle(principal, accountId, "mod_billing", null).data())
        .containsEntry("override", true);
    assertThat(controller.nudge(principal, null).data()).containsEntry("nudge_sent_count", 0);
    verify(adoption).listModules(principal, null, null, null, null);
  }
}
