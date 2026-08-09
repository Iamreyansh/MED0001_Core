package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.LeadPipelineServiceAcTest.InMemoryLeadStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.CrmLead;
import com.nammamedmate.crm.domain.LeadSource;
import com.nammamedmate.crm.domain.LeadStage;
import com.nammamedmate.crm.domain.LostReason;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeadPipelineServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SaasPlanStore plans;
  @Mock SubscriptionService subscriptions;

  InMemoryLeadStore store;
  LeadPipelineService service;
  MedmatePrincipal admin;
  MedmatePrincipal ops;
  UUID repId;

  @BeforeEach
  void setUp() {
    store = new InMemoryLeadStore();
    repId = Ids.newId();
    store.reps.put(repId, "Rep");
    store.repOrder.add(repId);
    service =
        new LeadPipelineService(
            store, plans, subscriptions, (t, id, p) -> {}, Clock.fixed(NOW, ZoneOffset.UTC));
    admin = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    store.reps.put(admin.subject(), "Super");
    store.reps.put(ops.subject(), "Ops");
  }

  @Test
  void updateAdvanceReopenAndNbaBranches() {
    when(plans.findPlanByName(PlanNames.STARTER))
        .thenReturn(
            Optional.of(
                new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 100, true, false, NOW)));
    Map<String, Object> created =
        service.create(
            ops, "P", "C", "+919900000001", "e@x.com", "AD", "STARTER", null, repId, null);
    UUID id = (UUID) created.get("id");
    service.update(ops, id, repId, new BigDecimal("1499"), 45, "note", true, true, true, true);
    service.advance(ops, id, "contacted"); // NEW→CONTACTED
    service.advance(ops, id, "demo"); // CONTACTED→DEMO
    Map<String, Object> detail = service.get(ops, id);
    assertThat(detail.get("next_best_action")).isNotNull();
    assertThat(detail.get("win_probability")).isEqualTo(30);

    // days-in-stage NBA variants
    CrmLead lead = store.leads.get(id);
    store.leads.put(
        id,
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.CONTACTED,
            10,
            lead.estimatedMrrPaise(),
            lead.targetPlan(),
            lead.assignedRepId(),
            lead.notes(),
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW.minusSeconds(4L * 86400)));
    assertThat(service.get(ops, id).get("next_best_action")).asString().contains("Schedule");

    store.leads.put(
        id,
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.TRIAL,
            60,
            lead.estimatedMrrPaise(),
            lead.targetPlan(),
            lead.assignedRepId(),
            lead.notes(),
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW.minusSeconds(8L * 86400)));
    assertThat(service.get(ops, id).get("next_best_action")).asString().contains("nudge");

    store.leads.put(
        id,
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.TRIAL,
            60,
            lead.estimatedMrrPaise(),
            lead.targetPlan(),
            lead.assignedRepId(),
            lead.notes(),
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW));
    assertThatThrownBy(() -> service.advance(ops, id, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    service.markLost(ops, id, LostReason.TIMELINE, "later");
    assertThat(service.get(ops, id).get("next_best_action")).asString().contains("reopen");
    service.reopen(ops, id);

    // marketplace idempotent + null guards
    service.ensureMarketplaceLead(null, null, null, null, null);
    UUID pharmacyId = Ids.newId();
    service.ensureMarketplaceLead(pharmacyId, "M", "O", "+919900000099", null);
    service.ensureMarketplaceLead(pharmacyId, "M", "O", "+919900000099", null);

    assertThatThrownBy(
            () -> service.update(ops, id, null, null, 200, null, false, false, true, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.markWon(ops, id, null, "MONTHLY"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID openNoPharm =
        (UUID)
            service
                .create(
                    ops,
                    "Q",
                    "D",
                    "+919900000002",
                    null,
                    "PARTNER",
                    null,
                    BigDecimal.TEN,
                    null,
                    null)
                .get("id");
    assertThatThrownBy(() -> service.markWon(ops, openNoPharm, Ids.newId(), "MONTHLY"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.get(ops, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_NOT_FOUND");

    assertThatThrownBy(() -> service.reopen(ops, openNoPharm))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () -> service.create(null, "A", "B", "+1", null, "ORGANIC", null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "s");
    assertThatThrownBy(() -> service.list(support, null, null, null, null, 0, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(
            () -> service.create(ops, " ", "B", "+1", null, "ORGANIC", null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    store.repOrder.clear();
    service.ensureMarketplaceLead(Ids.newId(), "", "", "+919900000088", "x@y.com");
  }

  @Test
  void markWonLostGuardsAndFilters() {
    UUID pharmacyId = Ids.newId();
    UUID planId = Ids.newId();
    when(plans.findPlanById(planId))
        .thenReturn(
            Optional.of(new SaasPlan(planId, PlanNames.STARTER, 69900, 2, 100, true, false, NOW)));
    when(subscriptions.subscribeOrUpgradeForPharmacy(any(), any(), any(), any()))
        .thenReturn(Map.of("subscription_id", Ids.newId()));
    when(plans.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());

    UUID id =
        (UUID)
            service
                .create(
                    admin,
                    "FilterCo",
                    "Ann",
                    "+919911111111",
                    null,
                    LeadSource.REFERRAL,
                    null,
                    new BigDecimal("100"),
                    repId,
                    pharmacyId)
                .get("id");
    service.list(admin, LeadStage.NEW, repId, LeadSource.REFERRAL, "Filter", 1, 5);
    service.markWon(admin, id, planId, null);
    assertThatThrownBy(() -> service.markWon(admin, id, planId, "MONTHLY"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_WON");
    assertThatThrownBy(() -> service.markLost(admin, id, LostReason.PRICE, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_WON");
    assertThatThrownBy(
            () -> service.update(admin, id, null, null, null, "x", false, false, false, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_WON");

    UUID lost =
        (UUID)
            service
                .create(admin, "L", "B", "+919922222222", null, "ORGANIC", null, null, null, null)
                .get("id");
    service.markLost(admin, lost, LostReason.COMPETITOR, null);
    assertThatThrownBy(() -> service.markLost(admin, lost, LostReason.PRICE, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_LOST");
    assertThatThrownBy(() -> service.markWon(admin, lost, planId, "MONTHLY"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_LOST");
    assertThatThrownBy(
            () -> service.update(admin, lost, null, null, null, "x", false, false, false, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_LOST");

    assertThat(service.get(admin, id).get("next_best_action")).asString().contains("Celebrate");
  }
}
