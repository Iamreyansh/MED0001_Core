package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.SaasPlanService;
import com.nammamedmate.crm.application.SubscriptionService;
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
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PharmacySubscriptionControllerTest {

  @Mock SaasPlanService plans;
  @Mock SubscriptionService subscriptions;
  PharmacySubscriptionController controller;
  UUID pharmacyId;
  MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    controller = new PharmacySubscriptionController(plans, subscriptions);
    pharmacyId = Ids.newId();
    owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
  }

  @Test
  void listPlansDelegates() {
    when(plans.listPlansForPharmacy(owner)).thenReturn(Map.of("current_plan", "STARTER"));
    assertThat(controller.listPlans(owner).data()).containsEntry("current_plan", "STARTER");
  }

  @Test
  void subscribeUpgradeDowngradeCancelGetAutoRenew() {
    UUID planId = Ids.newId();
    when(subscriptions.subscribe(owner, planId, "MONTHLY", null, "idem-1"))
        .thenReturn(Map.of("plan", "STARTER"));
    when(subscriptions.subscribe(owner, null, null, null, null)).thenReturn(Map.of("plan", "X"));
    when(subscriptions.upgrade(owner, planId, "idem-2"))
        .thenReturn(Map.of("new_plan", "RETAIL_PRO"));
    when(subscriptions.upgrade(owner, null, null)).thenReturn(Map.of("new_plan", "X"));
    when(subscriptions.downgrade(owner, planId)).thenReturn(Map.of("scheduled_plan", "STARTER"));
    when(subscriptions.downgrade(owner, null)).thenReturn(Map.of("scheduled_plan", "X"));
    when(subscriptions.cancel(owner)).thenReturn(Map.of("status", "ACTIVE"));
    when(subscriptions.getCurrent(owner)).thenReturn(Map.of("plan", "STARTER"));
    when(subscriptions.setAutoRenew(owner, false)).thenReturn(Map.of("auto_renew", false));
    when(subscriptions.setAutoRenew(owner, true)).thenReturn(Map.of("auto_renew", true));

    assertThat(
            controller
                .subscribe(
                    owner,
                    "idem-1",
                    new PharmacySubscriptionController.SubscribeRequest(planId, "MONTHLY", null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.subscribe(owner, null, null).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(
            controller
                .upgrade(owner, "idem-2", new PharmacySubscriptionController.UpgradeRequest(planId))
                .data())
        .containsEntry("new_plan", "RETAIL_PRO");
    assertThat(controller.upgrade(owner, null, null).data()).containsEntry("new_plan", "X");
    assertThat(
            controller
                .downgrade(owner, new PharmacySubscriptionController.DowngradeRequest(planId))
                .data())
        .containsEntry("scheduled_plan", "STARTER");
    assertThat(controller.downgrade(owner, null).data()).containsEntry("scheduled_plan", "X");
    assertThat(controller.cancel(owner).data()).containsEntry("status", "ACTIVE");
    assertThat(controller.current(owner).data()).containsEntry("plan", "STARTER");
    assertThat(
            controller
                .autoRenew(owner, new PharmacySubscriptionController.AutoRenewRequest(false))
                .data())
        .containsEntry("auto_renew", false);
    assertThat(controller.autoRenew(owner, null).data()).containsEntry("auto_renew", false);
    assertThat(
            controller
                .autoRenew(owner, new PharmacySubscriptionController.AutoRenewRequest(null))
                .data())
        .containsEntry("auto_renew", false);
    assertThat(
            controller
                .autoRenew(owner, new PharmacySubscriptionController.AutoRenewRequest(true))
                .data())
        .containsEntry("auto_renew", true);
  }
}
