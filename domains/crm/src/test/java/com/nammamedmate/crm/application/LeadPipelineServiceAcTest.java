package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmLeadOutboxPort;
import com.nammamedmate.crm.application.port.out.SaasLeadStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.CrmLead;
import com.nammamedmate.crm.domain.CrmLeadActivity;
import com.nammamedmate.crm.domain.LeadSource;
import com.nammamedmate.crm.domain.LeadStage;
import com.nammamedmate.crm.domain.LostReason;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.crm.domain.SubscriptionStatus;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeadPipelineServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID STARTER_ID = UUID.fromString("a1000000-0000-4000-8000-000000000002");
  private static final UUID RETAIL_ID = UUID.fromString("a1000000-0000-4000-8000-000000000003");

  @Mock SaasPlanStore plans;
  @Mock SubscriptionService subscriptions;
  @Mock CrmLeadOutboxPort outbox;

  InMemoryLeadStore store;
  LeadPipelineService service;
  MedmatePrincipal admin;
  MedmatePrincipal finance;
  UUID repId;
  List<Map<String, Object>> outboxEvents;

  @BeforeEach
  void setUp() {
    outboxEvents = new ArrayList<>();
    store = new InMemoryLeadStore();
    repId = Ids.newId();
    store.reps.put(repId, "Sneha Rao");
    store.repOrder.add(repId);
    service =
        new LeadPipelineService(
            store,
            plans,
            subscriptions,
            (type, id, payload) ->
                outboxEvents.add(Map.of("type", type, "id", id, "payload", payload)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    admin = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    store.reps.put(admin.subject(), "Admin Super");
    lenient()
        .when(plans.findPlanByName(PlanNames.STARTER))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    lenient()
        .when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    lenient()
        .when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
  }

  @Test
  @DisplayName("AC-001 Marketplace registration auto-creates CONTACTED MARKETPLACE lead")
  void ac001_marketplace() {
    UUID pharmacyId = Ids.newId();
    service.ensureMarketplaceLead(
        pharmacyId, "Sri Ram Medical", "Ramesh", "+919876543210", "r@x.com");
    assertThat(store.leads.values()).hasSize(1);
    CrmLead lead = store.leads.values().iterator().next();
    assertThat(lead.stage()).isEqualTo(LeadStage.CONTACTED);
    assertThat(lead.source()).isEqualTo(LeadSource.MARKETPLACE);
    assertThat(lead.winProbability()).isEqualTo(10);
    assertThat(lead.assignedRepId()).isEqualTo(repId);
    assertThat(outboxEvents).anyMatch(e -> "crm.lead.assigned".equals(e.get("type")));
  }

  @Test
  @DisplayName("AC-002 Manual create at NEW with win probability 0")
  void ac002_create() {
    Map<String, Object> data =
        service.create(
            admin,
            "Sri Ram Medical",
            "Ramesh",
            "+919876543210",
            "r@x.com",
            "REFERRAL",
            "STARTER",
            new BigDecimal("699"),
            repId,
            null);
    assertThat(data.get("stage")).isEqualTo(LeadStage.NEW);
    assertThat(data.get("win_probability")).isEqualTo(0);
    LeadPipelineService.PagedResult list = service.list(admin, null, null, null, null, 1, 20);
    assertThat(list.data().get("leads")).asList().isNotEmpty();
  }

  @Test
  @DisplayName("AC-003 Advance CONTACTED → DEMO sets win probability 30")
  void ac003_advance() {
    UUID id = seedOpen(LeadStage.CONTACTED, 10, 149900L);
    Map<String, Object> data = service.advance(admin, id, "Demo scheduled.");
    assertThat(data.get("previous_stage")).isEqualTo(LeadStage.CONTACTED);
    assertThat(data.get("new_stage")).isEqualTo(LeadStage.DEMO);
    assertThat(data.get("win_probability")).isEqualTo(30);
  }

  @Test
  @DisplayName("AC-004 Mark-won creates subscription and returns subscription_id")
  void ac004_markWon() {
    UUID pharmacyId = Ids.newId();
    UUID accountId = Ids.newId();
    UUID subId = Ids.newId();
    UUID id = seedWithPharmacy(LeadStage.TRIAL, 60, 149900L, pharmacyId);
    when(subscriptions.subscribeOrUpgradeForPharmacy(
            eq(pharmacyId), eq(RETAIL_ID), eq("MONTHLY"), anyString()))
        .thenReturn(Map.of("subscription_id", subId));
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));

    Map<String, Object> data = service.markWon(admin, id, RETAIL_ID, "MONTHLY");
    assertThat(data.get("stage")).isEqualTo(LeadStage.WON);
    assertThat(data.get("subscription_created")).isEqualTo(true);
    assertThat(data.get("subscription_id")).isEqualTo(subId);
    assertThat(data.get("sales_cycle_days")).isEqualTo(0);
    verify(subscriptions)
        .subscribeOrUpgradeForPharmacy(eq(pharmacyId), eq(RETAIL_ID), eq("MONTHLY"), anyString());
  }

  @Test
  @DisplayName("AC-005 Weighted forecast = Σ(estimated_mrr × win_probability/100)")
  void ac005_weightedForecast() {
    seedOpen(LeadStage.DEMO, 30, 100000L); // 30000 weighted
    seedOpen(LeadStage.TRIAL, 60, 200000L); // 120000 weighted
    LeadPipelineService.PagedResult list = service.list(admin, null, null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) list.data().get("chips");
    assertThat(chips.get("weighted_forecast_mrr_rs")).isEqualTo(new BigDecimal("1500.00"));
    assertThat(chips.get("pipeline_mrr_rs")).isEqualTo(new BigDecimal("3000.00"));
  }

  @Test
  @DisplayName("AC-006 Mark-lost with PRICE")
  void ac006_markLost() {
    UUID id = seedOpen(LeadStage.DEMO, 30, 149900L);
    Map<String, Object> data = service.markLost(admin, id, LostReason.PRICE, "Too expensive");
    assertThat(data.get("stage")).isEqualTo(LeadStage.LOST);
    assertThat(data.get("lost_reason")).isEqualTo(LostReason.PRICE);
  }

  @Test
  @DisplayName("AC-007 Reopen LOST → CONTACTED win 10%")
  void ac007_reopen() {
    UUID id = seedOpen(LeadStage.DEMO, 30, 149900L);
    service.markLost(admin, id, LostReason.PRICE, null);
    Map<String, Object> data = service.reopen(admin, id);
    assertThat(data.get("stage")).isEqualTo(LeadStage.CONTACTED);
    assertThat(data.get("win_probability")).isEqualTo(10);
  }

  @Test
  @DisplayName("AC-008 avg_sales_cycle_days from WON leads in period")
  void ac008_avgCycle() {
    UUID pharmacyId = Ids.newId();
    UUID id = seedWithPharmacy(LeadStage.DEMO, 30, 149900L, pharmacyId);
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
            lead.estimatedMrrPaise(),
            lead.targetPlan(),
            lead.assignedRepId(),
            lead.notes(),
            lead.lostReason(),
            lead.wonAt(),
            lead.lostAt(),
            lead.salesCycleDays(),
            lead.linkedAccountId(),
            lead.pharmacyId(),
            NOW.minusSeconds(14L * 86400),
            lead.updatedAt()));
    when(subscriptions.subscribeOrUpgradeForPharmacy(any(), any(), any(), any()))
        .thenReturn(Map.of("subscription_id", Ids.newId()));
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    Ids.newId(), pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    service.markWon(admin, id, STARTER_ID, "MONTHLY");
    LeadPipelineService.PagedResult list = service.list(admin, null, null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) list.data().get("chips");
    assertThat(chips.get("avg_sales_cycle_days")).isEqualTo(new BigDecimal("14"));
  }

  @Test
  @DisplayName("AC-009 Activity timeline shows stage changes with actor")
  void ac009_timeline() {
    UUID id = seedOpen(LeadStage.CONTACTED, 10, 149900L);
    service.advance(admin, id, "Demo scheduled.");
    Map<String, Object> detail = service.get(admin, id);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> timeline =
        (List<Map<String, Object>>) detail.get("activity_timeline");
    assertThat(timeline).anyMatch(e -> "STAGE_CHANGE".equals(e.get("event")));
    assertThat(timeline)
        .filteredOn(e -> "STAGE_CHANGE".equals(e.get("event")))
        .first()
        .satisfies(
            e -> {
              assertThat(e.get("from")).isEqualTo(LeadStage.CONTACTED);
              assertThat(e.get("to")).isEqualTo(LeadStage.DEMO);
              assertThat(e.get("actor")).isEqualTo("Admin Super");
              assertThat(e.get("at")).isNotNull();
            });
  }

  @Test
  @DisplayName("AC-010 Advance WON/LOST returns error codes")
  void ac010_advanceTerminal() {
    UUID wonId = seedOpen(LeadStage.DEMO, 30, 10000L);
    UUID pharmacyId = Ids.newId();
    store.leads.computeIfPresent(
        wonId,
        (k, lead) ->
            new CrmLead(
                lead.id(),
                lead.pharmacyName(),
                lead.contactName(),
                lead.phone(),
                lead.email(),
                lead.source(),
                lead.stage(),
                lead.winProbability(),
                lead.estimatedMrrPaise(),
                lead.targetPlan(),
                lead.assignedRepId(),
                lead.notes(),
                lead.lostReason(),
                lead.wonAt(),
                lead.lostAt(),
                lead.salesCycleDays(),
                lead.linkedAccountId(),
                pharmacyId,
                lead.createdAt(),
                lead.updatedAt()));
    when(subscriptions.subscribeOrUpgradeForPharmacy(any(), any(), any(), any()))
        .thenReturn(Map.of("subscription_id", Ids.newId()));
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    Ids.newId(), pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    service.markWon(admin, wonId, STARTER_ID, "MONTHLY");
    assertThatThrownBy(() -> service.advance(admin, wonId, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_WON");

    UUID lostId = seedOpen(LeadStage.DEMO, 30, 10000L);
    service.markLost(admin, lostId, LostReason.OTHER, null);
    assertThatThrownBy(() -> service.advance(admin, lostId, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_LOST");
  }

  @Test
  void invalidRepAndDuplicate() {
    assertThatThrownBy(
            () ->
                service.create(
                    admin, "A", "B", "+911", null, "ORGANIC", null, null, Ids.newId(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REP");
    service.create(admin, "A", "B", "+911", null, "ORGANIC", null, null, repId, null);
    assertThatThrownBy(
            () -> service.create(admin, "A", "B", "+911", null, "ORGANIC", null, null, repId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LEAD_ALREADY_EXISTS");
  }

  @Test
  void financeReadOnly() {
    seedOpen(LeadStage.NEW, 0, 1000L);
    assertThat(service.list(finance, null, null, null, null, 1, 20).data().get("leads"))
        .asList()
        .isNotEmpty();
    assertThatThrownBy(
            () ->
                service.create(finance, "A", "B", "+912", null, "ORGANIC", null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  private UUID seedOpen(String stage, int win, long mrrPaise) {
    return seedWithPharmacy(stage, win, mrrPaise, null);
  }

  private UUID seedWithPharmacy(String stage, int win, long mrrPaise, UUID pharmacyId) {
    UUID id = Ids.newId();
    CrmLead lead =
        new CrmLead(
            id,
            "Pharmacy " + id.toString().substring(0, 8),
            "Contact",
            "+91" + id.toString().replace("-", "").substring(0, 10),
            null,
            LeadSource.ORGANIC,
            stage,
            win,
            mrrPaise,
            "STARTER",
            repId,
            null,
            null,
            null,
            null,
            null,
            null,
            pharmacyId,
            NOW,
            NOW);
    store.insert(lead);
    store.insertActivity(
        new CrmLeadActivity(Ids.newId(), id, "CREATED", null, stage, null, null, "SYSTEM", NOW));
    return id;
  }

  private static SaasPlan plan(UUID id, String name, long paise) {
    return new SaasPlan(id, name, paise, 2, 100, true, false, NOW);
  }

  static final class InMemoryLeadStore implements SaasLeadStore {
    final Map<UUID, CrmLead> leads = new HashMap<>();
    final List<CrmLeadActivity> activities = new ArrayList<>();
    final Map<UUID, String> reps = new HashMap<>();
    final List<UUID> repOrder = new ArrayList<>();
    final AtomicReference<UUID> rr = new AtomicReference<>();

    @Override
    public void insert(CrmLead lead) {
      leads.put(lead.id(), lead);
    }

    @Override
    public void update(CrmLead lead) {
      leads.put(lead.id(), lead);
    }

    @Override
    public Optional<CrmLead> findById(UUID id) {
      return Optional.ofNullable(leads.get(id));
    }

    @Override
    public boolean existsOpenByPhone(String phone, UUID excludeLeadId) {
      return leads.values().stream()
          .anyMatch(
              l ->
                  phone.equals(l.phone())
                      && LeadStage.isOpen(l.stage())
                      && (excludeLeadId == null || !excludeLeadId.equals(l.id())));
    }

    @Override
    public boolean existsOpenByPharmacyId(UUID pharmacyId, UUID excludeLeadId) {
      if (pharmacyId == null) {
        return false;
      }
      return leads.values().stream()
          .anyMatch(
              l ->
                  pharmacyId.equals(l.pharmacyId())
                      && LeadStage.isOpen(l.stage())
                      && (excludeLeadId == null || !excludeLeadId.equals(l.id())));
    }

    @Override
    public List<CrmLead> list(
        String stage, UUID repId, String source, String q, int offset, int limit) {
      return leads.values().stream()
          .filter(l -> stage == null || stage.equals(l.stage()))
          .filter(l -> repId == null || repId.equals(l.assignedRepId()))
          .filter(l -> source == null || source.equals(l.source()))
          .filter(
              l ->
                  q == null
                      || q.isBlank()
                      || l.pharmacyName().toLowerCase().contains(q.toLowerCase())
                      || l.contactName().toLowerCase().contains(q.toLowerCase()))
          .sorted(Comparator.comparing(CrmLead::createdAt).reversed())
          .skip(offset)
          .limit(limit)
          .collect(Collectors.toList());
    }

    @Override
    public long count(String stage, UUID repId, String source, String q) {
      return list(stage, repId, source, q, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public PipelineChips chips(Instant periodFrom, Instant periodTo) {
      List<CrmLead> open =
          leads.values().stream().filter(l -> LeadStage.isOpen(l.stage())).toList();
      long pipeline =
          open.stream()
              .mapToLong(l -> l.estimatedMrrPaise() == null ? 0L : l.estimatedMrrPaise())
              .sum();
      long weighted =
          open.stream()
              .mapToLong(
                  l ->
                      ((l.estimatedMrrPaise() == null ? 0L : l.estimatedMrrPaise())
                              * l.winProbability())
                          / 100)
              .sum();
      long avg = open.isEmpty() ? 0L : pipeline / open.size();
      long won =
          leads.values().stream()
              .filter(l -> LeadStage.WON.equals(l.stage()))
              .filter(
                  l ->
                      l.wonAt() != null
                          && !l.wonAt().isBefore(periodFrom)
                          && !l.wonAt().isAfter(periodTo))
              .count();
      long lost =
          leads.values().stream()
              .filter(l -> LeadStage.LOST.equals(l.stage()))
              .filter(
                  l ->
                      l.lostAt() != null
                          && !l.lostAt().isBefore(periodFrom)
                          && !l.lostAt().isAfter(periodTo))
              .count();
      double winRate = (won + lost) == 0 ? 0.0 : (won * 100.0) / (won + lost);
      double avgCycle =
          leads.values().stream()
              .filter(l -> LeadStage.WON.equals(l.stage()) && l.salesCycleDays() != null)
              .filter(
                  l ->
                      l.wonAt() != null
                          && !l.wonAt().isBefore(periodFrom)
                          && !l.wonAt().isAfter(periodTo))
              .mapToInt(CrmLead::salesCycleDays)
              .average()
              .orElse(0.0);
      return new PipelineChips(open.size(), pipeline, weighted, avg, winRate, avgCycle);
    }

    @Override
    public Map<String, Long> openStageFunnel() {
      Map<String, Long> m = new HashMap<>();
      for (String s :
          List.of(LeadStage.NEW, LeadStage.CONTACTED, LeadStage.DEMO, LeadStage.TRIAL)) {
        m.put(s, 0L);
      }
      for (CrmLead l : leads.values()) {
        if (m.containsKey(l.stage())) {
          m.put(l.stage(), m.get(l.stage()) + 1);
        }
      }
      return m;
    }

    @Override
    public void insertActivity(CrmLeadActivity activity) {
      activities.add(activity);
    }

    @Override
    public List<CrmLeadActivity> listActivities(UUID leadId) {
      return activities.stream().filter(a -> a.leadId().equals(leadId)).toList();
    }

    @Override
    public Optional<RepRef> findActiveRep(UUID repId) {
      return Optional.ofNullable(reps.get(repId)).map(n -> new RepRef(repId, n));
    }

    @Override
    public Optional<String> findRepName(UUID repId) {
      return Optional.ofNullable(reps.get(repId));
    }

    @Override
    public List<UUID> listActiveRepIds() {
      return List.copyOf(repOrder);
    }

    @Override
    public Optional<UUID> nextRoundRobinRepId() {
      if (repOrder.isEmpty()) {
        return Optional.empty();
      }
      UUID last = rr.get();
      int idx = 0;
      if (last != null) {
        int found = repOrder.indexOf(last);
        idx = found < 0 ? 0 : (found + 1) % repOrder.size();
      }
      UUID next = repOrder.get(idx);
      rr.set(next);
      return Optional.of(next);
    }
  }
}
