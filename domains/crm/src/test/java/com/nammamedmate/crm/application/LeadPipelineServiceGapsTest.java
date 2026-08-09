package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.LeadPipelineServiceAcTest.InMemoryLeadStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.CrmLead;
import com.nammamedmate.crm.domain.CrmLeadActivity;
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
class LeadPipelineServiceGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SaasPlanStore plans;
  @Mock SubscriptionService subscriptions;

  InMemoryLeadStore store;
  LeadPipelineService service;
  MedmatePrincipal admin;
  UUID repA;
  UUID repB;

  @BeforeEach
  void setUp() {
    store = new InMemoryLeadStore();
    repA = Ids.newId();
    repB = Ids.newId();
    store.reps.put(repA, "A");
    store.reps.put(repB, "B");
    store.repOrder.add(repA);
    store.repOrder.add(repB);
    service =
        new LeadPipelineService(
            store, plans, subscriptions, (t, id, p) -> {}, Clock.fixed(NOW, ZoneOffset.UTC));
    admin = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    store.reps.put(admin.subject(), "Super");
  }

  @Test
  void remainingBranches() {
    assertThat(new LeadPipelineService.PagedResult(null, null).data()).isEmpty();
    service.list(admin, " ", null, " ", null, null, null);
    service.list(admin, LeadStage.NEW, null, LeadSource.ORGANIC, "x", 1, 5);
    service.list(admin, null, null, null, null, 0, 0);
    service.list(admin, null, null, null, null, 1, 150);

    UUID pharmacyDup = Ids.newId();
    service.create(
        admin, "P1", "C1", "+919900000010", null, "ORGANIC", null, null, null, pharmacyDup);
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    "P2",
                    "C2",
                    "+919900000011",
                    null,
                    "ORGANIC",
                    null,
                    null,
                    null,
                    pharmacyDup))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("LEAD_ALREADY_EXISTS");

    when(plans.findPlanByName("STARTER"))
        .thenReturn(
            Optional.of(
                new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 100, true, false, NOW)));
    UUID id =
        (UUID)
            service
                .create(
                    admin,
                    "NoRep",
                    "C",
                    "+919900000012",
                    " ",
                    "PARTNER",
                    "starter",
                    null,
                    null,
                    null)
                .get("id");
    Map<String, Object> detail = service.get(admin, id);
    assertThat(detail.get("assigned_rep")).isNull();
    assertThat(detail.get("estimated_mrr_rs")).isEqualTo(new BigDecimal("699.00"));

    // activity with null actor/notes + stage fields
    store.insertActivity(
        new CrmLeadActivity(
            Ids.newId(), id, "NOTE", LeadStage.NEW, LeadStage.NEW, null, null, null, NOW));
    assertThat(service.get(admin, id).get("activity_timeline")).asList().isNotEmpty();

    // reassignment + clear notes path + unassign
    service.update(admin, id, repA, null, null, "  ", true, true, false, true);
    service.update(admin, id, repB, BigDecimal.ONE, 20, "follow", true, true, true, true);
    service.update(admin, id, null, null, null, null, true, false, false, false); // unassign
    assertThatThrownBy(
            () -> service.update(admin, id, null, null, null, null, false, false, true, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
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
            lead.stage(),
            lead.winProbability(),
            null,
            lead.targetPlan(),
            repB,
            lead.notes(),
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW.plusSeconds(10)));
    // days negative NBA guard via updatedAt in future relative to clock? use past for DEMO days<2
    store.leads.put(
        id,
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.DEMO,
            30,
            null,
            lead.targetPlan(),
            repB,
            "keep",
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW));
    assertThat(service.get(admin, id).get("next_best_action")).asString().contains("demo");
    store.leads.put(
        id,
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.DEMO,
            30,
            null,
            lead.targetPlan(),
            repB,
            "keep",
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW.minusSeconds(3L * 86400)));
    assertThat(service.get(admin, id).get("next_best_action")).asString().contains("trial");
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
            null,
            lead.targetPlan(),
            repB,
            "keep",
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW));
    assertThat(service.get(admin, id).get("next_best_action")).asString().contains("Follow");
    store.leads.put(
        id,
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.NEW,
            0,
            null,
            lead.targetPlan(),
            repB,
            "keep",
            null,
            null,
            null,
            null,
            null,
            null,
            lead.createdAt(),
            NOW.plusSeconds(100)));
    assertThat(service.get(admin, id).get("next_best_action")).asString().contains("contact");

    // markLost notes merge branches
    UUID lostNotes =
        (UUID)
            service
                .create(admin, "L1", "C", "+919900000013", null, "AD", null, null, null, null)
                .get("id");
    service.markLost(admin, lostNotes, LostReason.OTHER, "reason note");
    UUID lostBlank =
        (UUID)
            service
                .create(admin, "L2", "C", "+919900000014", null, "AD", null, null, null, null)
                .get("id");
    service.markLost(admin, lostBlank, LostReason.OTHER, " ");
    UUID lostMerge =
        (UUID)
            service
                .create(admin, "L3", "C", "+919900000015", null, "AD", null, null, repA, null)
                .get("id");
    service.update(admin, lostMerge, null, null, null, "existing", false, false, false, true);
    service.markLost(admin, lostMerge, LostReason.OTHER, "more");

    // reopen conflict
    UUID openPhone =
        (UUID)
            service
                .create(admin, "O", "C", "+919900000016", null, "ORGANIC", null, null, null, null)
                .get("id");
    service.markLost(admin, openPhone, LostReason.PRICE, null);
    service.create(admin, "O2", "C", "+919900000016", null, "ORGANIC", null, null, null, null);
    assertThatThrownBy(() -> service.reopen(admin, openPhone))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("LEAD_ALREADY_EXISTS");

    // markWon plan missing + negative cycle days
    UUID planId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    UUID winId =
        (UUID)
            service
                .create(
                    admin,
                    "Win",
                    "C",
                    "+919900000017",
                    null,
                    "ORGANIC",
                    null,
                    null,
                    null,
                    pharmacyId)
                .get("id");
    when(plans.findPlanById(planId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.markWon(admin, winId, planId, "MONTHLY"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");
    when(plans.findPlanById(planId))
        .thenReturn(
            Optional.of(new SaasPlan(planId, PlanNames.STARTER, 69900, 2, 100, true, false, NOW)));
    when(subscriptions.subscribeOrUpgradeForPharmacy(any(), any(), any(), any()))
        .thenReturn(Map.of("subscription_id", Ids.newId()));
    when(plans.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());
    CrmLead winLead = store.leads.get(winId);
    store.leads.put(
        winId,
        new CrmLead(
            winLead.id(),
            winLead.pharmacyName(),
            winLead.contactName(),
            winLead.phone(),
            winLead.email(),
            winLead.source(),
            winLead.stage(),
            winLead.winProbability(),
            winLead.estimatedMrrPaise(),
            winLead.targetPlan(),
            winLead.assignedRepId(),
            winLead.notes(),
            null,
            null,
            null,
            null,
            null,
            pharmacyId,
            NOW.plusSeconds(86400),
            winLead.updatedAt()));
    service.markWon(admin, winId, planId, null);

    // validateCreateFields contact/phone
    assertThatThrownBy(
            () -> service.create(admin, "P", " ", "+1", null, "ORGANIC", null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.create(admin, "P", "C", " ", null, "ORGANIC", null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // write forbidden for finance
    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    assertThatThrownBy(() -> service.advance(finance, id, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(null, null, null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // toListItem null mrr / null rep name
    UUID listId =
        (UUID)
            service
                .create(
                    admin, "List", "C", "+919900000018", null, "ORGANIC", null, null, null, null)
                .get("id");
    CrmLead listLead = store.leads.get(listId);
    store.leads.put(
        listId,
        new CrmLead(
            listLead.id(),
            listLead.pharmacyName(),
            listLead.contactName(),
            listLead.phone(),
            listLead.email(),
            listLead.source(),
            listLead.stage(),
            0,
            null,
            null,
            Ids.newId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            listLead.createdAt(),
            listLead.updatedAt()));
    service.list(admin, null, null, null, null, 1, 50);

    // unknown stage NBA default
    store.leads.put(
        listId,
        new CrmLead(
            listLead.id(),
            listLead.pharmacyName(),
            listLead.contactName(),
            listLead.phone(),
            listLead.email(),
            listLead.source(),
            "WEIRD",
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            listLead.createdAt(),
            listLead.updatedAt()));
    assertThat(service.get(admin, listId).get("next_best_action")).isEqualTo("Review lead.");

    // TRIAL onboarding nba (<7 days)
    store.leads.put(
        listId,
        new CrmLead(
            listLead.id(),
            listLead.pharmacyName(),
            listLead.contactName(),
            listLead.phone(),
            listLead.email(),
            listLead.source(),
            LeadStage.TRIAL,
            60,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            listLead.createdAt(),
            NOW));
    assertThat(service.get(admin, listId).get("next_best_action")).asString().contains("Support");

    service.list(admin, null, null, null, null, 2, 50);
    service.ensureMarketplaceLead(Ids.newId(), null, null, "  ", null);
    service.ensureMarketplaceLead(Ids.newId(), null, null, null, null);
    service.ensureMarketplaceLead(Ids.newId(), null, " ", "+919900000020", null);
    service.ensureMarketplaceLead(Ids.newId(), " ", null, "+919900000021", null);
    // open by phone only (different pharmacy)
    service.ensureMarketplaceLead(Ids.newId(), "Dup", "D", "+919900000010", null);
    when(plans.findPlanByName("GHOST")).thenReturn(Optional.empty());
    service.create(
        admin, "GhostPlan", "C", "+919900000022", null, "ORGANIC", "ghost", null, null, null);
    service.create(
        admin, "BlankPlan", "C", "+919900000023", null, "ORGANIC", "  ", null, null, null);
    UUID sameRep =
        (UUID)
            service
                .create(
                    admin, "SameRep", "C", "+919900000024", null, "ORGANIC", null, null, repA, null)
                .get("id");
    service.update(admin, sameRep, repA, null, null, null, true, false, false, true);
    assertThatThrownBy(
            () -> service.update(admin, sameRep, null, null, -1, null, false, false, true, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    admin, null, "C", "+919900000025", null, "ORGANIC", null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    admin, "P", null, "+919900000025", null, "ORGANIC", null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.create(admin, "P", "C", null, null, "ORGANIC", null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
