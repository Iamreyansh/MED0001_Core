package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.SaasPlanService;
import com.nammamedmate.crm.application.SubscriptionService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCrmPlanControllerTest {

  @Mock SaasPlanService plans;
  @Mock SubscriptionService subscriptions;
  AdminCrmPlanController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminCrmPlanController(plans, subscriptions);
  }

  @Test
  void delegatesAllEndpoints() {
    UUID planId = Ids.newId();
    UUID accountId = Ids.newId();
    UUID addonId = Ids.newId();
    when(plans.listPlansAdmin(any())).thenReturn(Map.of("plans", java.util.List.of()));
    when(plans.getPlanAdmin(any(), eq(planId), isNull(), isNull()))
        .thenReturn(Map.of("plan_id", planId));
    when(plans.updatePlan(any(), eq(planId), any(), any(), any()))
        .thenReturn(Map.of("plan_id", planId));
    when(plans.listAddons(any())).thenReturn(Map.of("addons", java.util.List.of()));
    when(plans.attachAddon(any(), eq(accountId), eq(addonId)))
        .thenReturn(Map.of("account_id", accountId));
    when(plans.detachAddon(any(), eq(accountId), eq(addonId)))
        .thenReturn(Map.of("account_id", accountId));
    when(plans.moduleMatrix(any())).thenReturn(Map.of("modules", java.util.List.of()));
    Instant expires = Instant.parse("2026-10-24T00:00:00Z");
    when(subscriptions.overrideSubscription(any(), eq(accountId), eq(planId), any(), eq(expires)))
        .thenReturn(Map.of("override_plan", "RETAIL_PRO"));

    assertThat(controller.listPlans(principal).data()).containsKey("plans");
    assertThat(controller.getPlan(principal, planId, null, null).data())
        .containsEntry("plan_id", planId);
    ApiResponse<Map<String, Object>> patched =
        controller.updatePlan(
            principal,
            planId,
            new AdminCrmPlanController.UpdatePlanRequest(new BigDecimal("799.00"), 3, 600));
    assertThat(patched.data()).containsEntry("plan_id", planId);
    when(plans.updatePlan(any(), eq(planId), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("plan_id", planId));
    assertThat(controller.updatePlan(principal, planId, null).data())
        .containsEntry("plan_id", planId);
    assertThat(controller.listAddons(principal).data()).containsKey("addons");
    assertThat(controller.attachAddon(principal, accountId, addonId).data())
        .containsEntry("account_id", accountId);
    assertThat(controller.detachAddon(principal, accountId, addonId).data())
        .containsEntry("account_id", accountId);
    assertThat(controller.moduleMatrix(principal).data()).containsKey("modules");
    assertThat(
            controller
                .overrideSubscription(
                    principal,
                    accountId,
                    new AdminCrmPlanController.OverrideRequest(planId, "deal", expires))
                .data())
        .containsEntry("override_plan", "RETAIL_PRO");
    when(subscriptions.overrideSubscription(any(), eq(accountId), isNull(), isNull(), isNull()))
        .thenReturn(Map.of("override_plan", "FREE"));
    assertThat(controller.overrideSubscription(principal, accountId, null).data())
        .containsEntry("override_plan", "FREE");
    verify(subscriptions).overrideSubscription(principal, accountId, planId, "deal", expires);
  }
}
