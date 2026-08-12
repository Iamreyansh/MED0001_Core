package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.out.persistence.InMemoryRateLimitAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcSimulationStoreAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcTriggerEventStore;
import com.nammamedmate.automation.application.ActiveRuleCache;
import com.nammamedmate.automation.application.RuleManagementService;
import com.nammamedmate.automation.application.RuleSimulationService;
import com.nammamedmate.automation.application.SimulationJobProcessor;
import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.RateLimitPort;
import com.nammamedmate.automation.application.port.out.RuleAuditPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.application.port.out.SimulationNotifyPort;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.application.port.out.TriggerEventQueryPort;
import com.nammamedmate.automation.application.port.out.TriggerEventQueryPort.TriggerEventRow;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.SimulationRiskAssessor;
import com.nammamedmate.automation.domain.SimulationStatus;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.TriggerEntityTypes;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoverageStory004GapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:30:00Z");
  private static final UUID RULE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID SIM = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Mock RuleStorePort rules;
  @Mock SimulationStorePort simulations;
  @Mock TriggerEventQueryPort events;
  @Mock TriggerRegistryPort triggers;
  @Mock ActionRegistryPort actions;
  @Mock DedupPort dedup;
  @Mock RateLimitPort rateLimit;
  @Mock ActivityLogPort activityLog;
  @Mock RuleAuditPort audit;
  @Mock RuleLookupPort lookup;
  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock SimulationNotifyPort notify;

  private RuleSimulationService service;
  private RuleManagementService management;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final MedmatePrincipal admin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final ObjectMapper om = new ObjectMapper();

  @BeforeEach
  void setUp() {
    service =
        new RuleSimulationService(
            rules,
            simulations,
            events,
            triggers,
            actions,
            new ConditionEvaluator(clock),
            dedup,
            rateLimit,
            activityLog,
            clock);
    management =
        new RuleManagementService(
            rules,
            triggers,
            actions,
            audit,
            new ActiveRuleCache(lookup, clock),
            mock(SeedCatalogPort.class),
            clock);
    when(triggers.findById(anyString()))
        .thenReturn(
            Optional.of(
                new TriggerDefinition(
                    "invoice_overdue",
                    "FINANCE",
                    "n",
                    "d",
                    List.of(),
                    List.of("eq"),
                    List.of("invoice.id", "pharmacy.id"),
                    true)));
  }

  @Test
  void simulationServiceErrorBranches() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule(RuleStatus.INACTIVE)));
    assertThatThrownBy(() -> service.startBatch(admin, RULE_ID, 0, true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.startBatch(admin, RULE_ID, 10, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> ok = service.startBatch(admin, RULE_ID, null, null);
    assertThat(ok.get("sample_size")).isEqualTo(100);

    assertThatThrownBy(() -> service.getResults(admin, RULE_ID, SIM))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SIMULATION_NOT_FOUND");
    assertThatThrownBy(() -> service.preview(null, RULE_ID, "PHARMACY", ENTITY))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.preview(
                    new MedmatePrincipal(ADMIN, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    RULE_ID,
                    "PHARMACY",
                    ENTITY))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal ops =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule(RuleStatus.INACTIVE)));
    assertThat(service.startBatch(ops, RULE_ID, 10, true).get("status")).isEqualTo("RUNNING");
    assertThatThrownBy(() -> service.preview(admin, RULE_ID, " ", ENTITY))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_ENTITY_TYPE");
    assertThatThrownBy(() -> service.preview(admin, RULE_ID, "PHARMACY", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ENTITY_NOT_FOUND");
    when(triggers.findById("invoice_overdue")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.preview(admin, RULE_ID, "PHARMACY", ENTITY))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_TRIGGER");
    when(rules.findById(UUID.randomUUID())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.startBatch(admin, UUID.randomUUID(), 10, true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void processSimulationBranches() {
    service.processSimulation(SIM); // missing
    when(simulations.findById(SIM))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    SIM,
                    RULE_ID,
                    5,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    List.of(),
                    SimulationStatus.COMPLETED,
                    NOW,
                    NOW,
                    ADMIN,
                    null)));
    service.processSimulation(SIM); // not running

    when(simulations.findById(SIM))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    SIM,
                    RULE_ID,
                    5,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    List.of(),
                    SimulationStatus.RUNNING,
                    NOW,
                    null,
                    ADMIN,
                    null)));
    when(rules.findById(RULE_ID)).thenReturn(Optional.empty());
    service.processSimulation(SIM);
    verify(simulations).markFailed(eq(SIM), any(), anyString());

    when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule(RuleStatus.INACTIVE)));
    when(events.listRecentByTrigger(anyString(), anyInt()))
        .thenReturn(
            List.of(
                new TriggerEventRow(
                    UUID.randomUUID(),
                    "invoice_overdue",
                    "PHARMACY",
                    ENTITY,
                    Map.of("invoice.status", "PAID", "name", "Shop"),
                    NOW),
                new TriggerEventRow(
                    UUID.randomUUID(), "invoice_overdue", "PHARMACY", ENTITY, Map.of(), NOW)));
    when(actions.findById(anyString())).thenReturn(Optional.empty());
    doThrow(new RuntimeException("boom"))
        .when(simulations)
        .markCompleted(
            any(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
    // first call fails markCompleted path via exception in assess? Actually exception from
    // markCompleted
    when(rules.findById(RULE_ID))
        .thenReturn(
            Optional.of(
                new AutomationRule(
                    RULE_ID,
                    "r",
                    "d",
                    "invoice_overdue",
                    "FINANCE",
                    Map.of(),
                    List.of(new ConditionSpec("invoice.status", "eq", "OVERDUE")),
                    List.of(new ActionSpec("send_notification", Map.of(), false)),
                    Guardrails.NONE,
                    RuleStatus.INACTIVE,
                    0,
                    null,
                    false,
                    300,
                    ADMIN,
                    NOW,
                    NOW)));
    service.processSimulation(SIM);
    verify(simulations).markFailed(eq(SIM), any(), eq("boom"));
  }

  @Test
  void previewRateLimitAndNames() {
    when(rules.findById(RULE_ID))
        .thenReturn(
            Optional.of(
                new AutomationRule(
                    RULE_ID,
                    "r",
                    "d",
                    "invoice_overdue",
                    "FINANCE",
                    Map.of(),
                    List.of(),
                    List.of(new ActionSpec("send_notification", Map.of(), false)),
                    new Guardrails(new Guardrails.RateLimit(1, 60), null, null),
                    RuleStatus.INACTIVE,
                    0,
                    null,
                    false,
                    300,
                    ADMIN,
                    NOW,
                    NOW)));
    when(events.findLatestByEntity("PHARMACY", ENTITY))
        .thenReturn(
            Optional.of(
                new TriggerEventRow(
                    UUID.randomUUID(),
                    "invoice_overdue",
                    "PHARMACY",
                    ENTITY,
                    Map.of("pharmacy_name", "Medplus"),
                    NOW)));
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(true);
    when(rateLimit.wouldExceed(any(), anyInt(), anyInt())).thenReturn(true);
    when(actions.findById("send_notification"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "send_notification",
                    "NOTIFICATION",
                    "n",
                    "d",
                    List.of(),
                    List.of(),
                    false,
                    true,
                    null)));
    Map<String, Object> fireTrue = service.preview(admin, RULE_ID, "pharmacy", ENTITY);
    assertThat(fireTrue.get("would_be_deduplicated")).isEqualTo(true);
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(rateLimit.wouldExceed(any(), anyInt(), anyInt())).thenReturn(true);
    assertThat(service.preview(admin, RULE_ID, "PHARMACY", ENTITY).get("would_fire"))
        .isEqualTo(false);
    when(rateLimit.wouldExceed(any(), anyInt(), anyInt())).thenReturn(false);
    assertThat(service.preview(admin, RULE_ID, "PHARMACY", ENTITY).get("would_fire"))
        .isEqualTo(true);

    when(simulations.findByRuleAndId(RULE_ID, SIM))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    SIM,
                    RULE_ID,
                    10,
                    1,
                    1,
                    0,
                    null,
                    null,
                    null,
                    List.of(),
                    SimulationStatus.FAILED,
                    NOW,
                    null,
                    ADMIN,
                    null)));
    assertThat(service.getResults(admin, RULE_ID, SIM).get("completed_at")).isNull();
    assertThat(service.getResults(admin, RULE_ID, SIM).get("false_positive_risk")).isEqualTo("LOW");
  }

  @Test
  void autoRevertAndStatusTransitions() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.empty());
    management.autoRevertSimulating(RULE_ID);

    AutomationRule simulating =
        new AutomationRule(
            RULE_ID,
            "r",
            "d",
            "invoice_overdue",
            "FINANCE",
            Map.of(),
            List.of(),
            List.of(new ActionSpec("send_notification", Map.of(), false)),
            Guardrails.NONE,
            RuleStatus.SIMULATING,
            0,
            null,
            false,
            300,
            ADMIN,
            NOW,
            NOW);
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(simulating));
    when(actions.findById("send_notification"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "send_notification",
                    "NOTIFICATION",
                    "n",
                    "d",
                    List.of(),
                    List.of(),
                    true,
                    false,
                    null)));
    management.setStatus(admin, RULE_ID, "SIMULATING");
    verify(rules).markSimulatingStarted(eq(RULE_ID), any());
    management.setStatus(admin, RULE_ID, "INACTIVE");
    verify(rules).clearSimulatingStarted(RULE_ID);
    management.autoRevertSimulating(RULE_ID);

    when(rules.listSimulatingStartedBefore(any(), anyInt())).thenReturn(List.of(RULE_ID));
    new SimulationJobProcessor(simulations, service, rules, management, notify, clock)
        .revertExpiredSimulating();
    verify(notify).simulatingAutoReverted(eq(RULE_ID), eq(ADMIN), eq("r"));
  }

  @Test
  void triggerEntityTypesAllBranches() {
    java.util.ArrayList<String> vars = new java.util.ArrayList<>();
    for (String v :
        List.of(
            "order.id",
            "pharmacy.id",
            "rider.id",
            "ticket.id",
            "customer.id",
            "payment.id",
            "payout.id",
            "entity.id",
            "coupon.id",
            "prescription.id",
            "invoice.id",
            "register.id",
            "sale.id",
            "sku.id",
            "csat.score",
            "campaign.id",
            "segment.id",
            "refund.id",
            "unknown.x",
            " ")) {
      vars.add(v);
    }
    assertThat(
            TriggerEntityTypes.allowed(
                new TriggerDefinition("t", "GROWTH", "n", "d", List.of(), List.of(), vars, true)))
        .contains("ORDER", "PHARMACY", "RIDER", "TICKET", "CUSTOMER");
    for (String cat :
        List.of(
            "ORDERS",
            "DISPATCH",
            "PHARMACY",
            "CRM",
            "COMPLIANCE",
            "RIDER",
            "SUPPORT",
            "FINANCE",
            "GROWTH",
            "OTHER")) {
      assertThat(
              TriggerEntityTypes.allowed(
                  new TriggerDefinition("t", cat, "n", "d", List.of(), List.of(), List.of(), true)))
          .isNotEmpty();
    }
    assertThat(TriggerEntityTypes.allowed(null)).isEmpty();
    assertThat(
            TriggerEntityTypes.allowed(
                new TriggerDefinition(
                    "t", null, "n", "d", List.of(), List.of(), List.of("order"), true)))
        .contains("ORDER");
    assertThat(
            TriggerEntityTypes.allowed(
                new TriggerDefinition("t", null, "n", "d", List.of(), List.of(), List.of(), true)))
        .contains("ENTITY");
    assertThat(TriggerEntityTypes.primaryLabel(Set.of("ORDER"))).isEqualTo("order");
    assertThat(TriggerEntityTypes.primaryLabel(Set.of("RIDER"))).isEqualTo("rider");
    assertThat(TriggerEntityTypes.primaryLabel(Set.of("TICKET"))).isEqualTo("ticket");
    assertThat(TriggerEntityTypes.primaryLabel(Set.of("CUSTOMER"))).isEqualTo("customer");
    assertThat(TriggerEntityTypes.primaryLabel(Set.of("X"))).isEqualTo("entity");
  }

  @Test
  void riskAssessorBranches() {
    var a =
        SimulationRiskAssessor.assess(
            100,
            List.of(
                Map.of("action", "suspend_entity", "entity_id", "1"),
                Map.of("action", "suspend_entity", "entity_id", "2"),
                Map.of("action", "suspend_entity", "entity_id", "3"),
                Map.of("action", "suspend_entity", "entity_id", "4"),
                Map.of("action", "suspend_entity", "entity_id", "5"),
                Map.of("action", "suspend_entity", "entity_id", "6")),
            "pharmacy",
            List.of(
                new ActionSpec("suspend_entity", Map.of(), false),
                new ActionSpec("send_notification", Map.of(), false)));
    assertThat(a.risk()).isEqualTo(FalsePositiveRisk.MEDIUM);
    assertThat(a.estimatedImpactSummary()).contains("pharmacies");

    var lowIrrev =
        SimulationRiskAssessor.assess(
            100, List.of(Map.of("action", "release_payout", "entity_id", "1")), "entity", null);
    assertThat(lowIrrev.risk()).isEqualTo(FalsePositiveRisk.LOW);

    assertThat(SimulationRiskAssessor.assess(1, null, null, List.of()).estimatedImpactSummary())
        .contains("entity");
    assertThat(SimulationRiskAssessor.assess(1, null, "", List.of()).estimatedImpactSummary())
        .contains("entity");
    assertThat(
            SimulationRiskAssessor.assess(
                    2, List.of(), "y", List.of(new ActionSpec("a", Map.of(), false)))
                .estimatedImpactSummary())
        .contains("ys");
    assertThat(
            SimulationRiskAssessor.assess(
                    2, List.of(), "day", List.of(new ActionSpec("a", Map.of(), false)))
                .estimatedImpactSummary())
        .contains("days");
    assertThat(
            SimulationRiskAssessor.assess(
                    2, List.of(), "cities", List.of(new ActionSpec("a", Map.of(), false)))
                .estimatedImpactSummary())
        .contains("cities");
    assertThat(
            SimulationRiskAssessor.assess(
                    2, List.of(), "orders", List.of(new ActionSpec("a", Map.of(), false)))
                .estimatedImpactSummary())
        .contains("orders");
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcEdgeBranches() throws Exception {
    JdbcSimulationStoreAdapter store = new JdbcSimulationStoreAdapter(jdbc, om);
    when(rs.getObject("id")).thenReturn(SIM);
    when(rs.getObject("rule_id")).thenReturn(RULE_ID);
    when(rs.getInt("sample_size")).thenReturn(1);
    when(rs.getInt("events_scanned")).thenReturn(0);
    when(rs.getInt("entities_matched")).thenReturn(0);
    when(rs.getInt("conditions_failed_count")).thenReturn(0);
    when(rs.getString("false_positive_risk")).thenReturn(null);
    when(rs.getString("risk_details")).thenReturn(null);
    when(rs.getString("estimated_impact_summary")).thenReturn(null);
    when(rs.getString("results_json")).thenReturn("not-json");
    when(rs.getString("status")).thenReturn("RUNNING");
    when(rs.getTimestamp("started_at")).thenReturn(null);
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    when(rs.getObject("triggered_by")).thenReturn(ADMIN);
    when(rs.getTimestamp("expires_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              return List.of(m.mapRow(rs, 0));
            });
    assertThat(store.findById(SIM)).isPresent();

    store.insert(
        new AutomationSimulation(
            SIM,
            RULE_ID,
            1,
            0,
            0,
            0,
            FalsePositiveRisk.HIGH,
            "d",
            "s",
            null,
            SimulationStatus.RUNNING,
            NOW,
            NOW,
            ADMIN,
            NOW));
    store.markCompleted(SIM, 1, 1, 0, null, "d", "s", null, NOW, NOW);

    ObjectMapper bad =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("x");
          }
        };
    JdbcSimulationStoreAdapter broken = new JdbcSimulationStoreAdapter(jdbc, bad);
    broken.insert(
        new AutomationSimulation(
            SIM,
            RULE_ID,
            1,
            0,
            0,
            0,
            null,
            null,
            null,
            List.of(),
            SimulationStatus.RUNNING,
            NOW,
            null,
            null,
            null));

    JdbcTriggerEventStore eventsStore = new JdbcTriggerEventStore(jdbc, om);
    when(rs.getString("payload")).thenReturn(" ");
    when(rs.getString("trigger_id")).thenReturn("t");
    when(rs.getString("entity_type")).thenReturn("ORDER");
    when(rs.getObject("entity_id")).thenReturn(ENTITY);
    when(rs.getTimestamp("fired_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              return List.of(m.mapRow(rs, 0));
            });
    when(rs.getString("payload")).thenReturn(null);
    assertThat(eventsStore.listRecentByTrigger("t", 1).getFirst().payload()).isEmpty();
    when(rs.getString("payload")).thenReturn(" ");
    assertThat(eventsStore.findLatestByEntity("ORDER", ENTITY)).isPresent();
    when(rs.getString("payload")).thenReturn("{bad");
    assertThat(eventsStore.listRecentByTrigger("t", 1).getFirst().payload()).isEmpty();

    InMemoryRateLimitAdapter lim = new InMemoryRateLimitAdapter(clock);
    UUID r = UUID.randomUUID();
    assertThat(lim.wouldExceed(r, 0, 60)).isFalse();
    assertThat(lim.wouldExceed(r, 1, 0)).isFalse();
    assertThat(lim.wouldExceed(UUID.randomUUID(), 5, 60)).isFalse();
    lim.tryAcquire(r, 2, 60);
    assertThat(lim.wouldExceed(r, 2, 60)).isFalse();
    lim.tryAcquire(r, 2, 60);
    assertThat(lim.wouldExceed(r, 2, 60)).isTrue();

    java.util.concurrent.atomic.AtomicReference<Instant> tick =
        new java.util.concurrent.atomic.AtomicReference<>(NOW);
    Clock moving =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return tick.get();
          }
        };
    InMemoryRateLimitAdapter lim2 = new InMemoryRateLimitAdapter(moving);
    UUID r2 = UUID.randomUUID();
    lim2.tryAcquire(r2, 1, 60);
    tick.set(NOW.plus(Duration.ofMinutes(61)));
    assertThat(lim2.wouldExceed(r2, 1, 60)).isFalse();

    RateLimitPort defaults = (ruleId, max, per) -> true;
    assertThat(defaults.wouldExceed(r, 1, 1)).isFalse();

    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<com.nammamedmate.messaging.OutboxPublisher>
        provider = mock(org.springframework.beans.factory.ObjectProvider.class);
    com.nammamedmate.messaging.OutboxPublisher publisher =
        mock(com.nammamedmate.messaging.OutboxPublisher.class);
    when(provider.getIfAvailable()).thenReturn(publisher);
    new com.nammamedmate.automation.adapter.out.persistence.StubSimulationNotifyAdapter(provider)
        .simulatingAutoReverted(null, ADMIN, "x");

    assertThat(
            SimulationRiskAssessor.assess(
                    1,
                    List.of(Map.of("action", "send_notification")),
                    "order",
                    List.of(new ActionSpec("send_notification", Map.of(), false)))
                .estimatedImpactSummary())
        .contains("order");
    assertThat(
            SimulationRiskAssessor.assess(
                    100,
                    List.of(Map.of("action", "suspend_entity", "entity_id", "1")),
                    "pharmacy",
                    List.of(new ActionSpec("suspend_entity", Map.of(), false)))
                .risk())
        .isEqualTo(FalsePositiveRisk.LOW);

    when(rs.getString("results_json")).thenReturn("");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> m = inv.getArgument(1);
              return List.of(m.mapRow(rs, 0));
            });
    assertThat(store.findById(SIM).orElseThrow().actionsThatWouldFire()).isEmpty();
    when(rs.getString("results_json")).thenReturn(null);
    assertThat(store.findById(SIM).orElseThrow().actionsThatWouldFire()).isEmpty();

    assertThat(SimulationStatus.parse("")).isNull();
    assertThat(new TriggerEventRow(SIM, "t", "ORDER", ENTITY, null, NOW).payload()).isEmpty();
    assertThat(
            SimulationRiskAssessor.assess(
                    2, List.of(), "day", List.of(new ActionSpec("a", Map.of(), false)))
                .estimatedImpactSummary())
        .contains("days");
    assertThat(TriggerEntityTypes.primaryLabel(null)).isEqualTo("entity");

    // controller null-body branches
    var ctrl =
        new com.nammamedmate.automation.adapter.in.web.AdminAutomationRulesController(
            management, service);
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule(RuleStatus.INACTIVE)));
    assertThat(ctrl.simulate(admin, RULE_ID, null).getStatusCode().value()).isEqualTo(202);
    assertThat(
            ctrl.simulate(
                    admin,
                    RULE_ID,
                    new com.nammamedmate.automation.adapter.in.web.AdminAutomationRulesController
                        .SimulateRequest(50, true))
                .getStatusCode()
                .value())
        .isEqualTo(202);
    when(events.findLatestByEntity(anyString(), any()))
        .thenReturn(
            Optional.of(
                new TriggerEventRow(
                    UUID.randomUUID(), "invoice_overdue", "PHARMACY", ENTITY, Map.of(), NOW)));
    assertThat(
            ctrl.simulationPreview(
                    admin,
                    RULE_ID,
                    new com.nammamedmate.automation.adapter.in.web.AdminAutomationRulesController
                        .PreviewRequest("PHARMACY", ENTITY))
                .success())
        .isTrue();
    assertThatThrownBy(() -> ctrl.simulationPreview(admin, RULE_ID, null))
        .isInstanceOf(AppException.class);

    // autoRevert when status not SIMULATING
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule(RuleStatus.INACTIVE)));
    management.autoRevertSimulating(RULE_ID);

    // processSimulation success path with def present + failed conditions
    when(simulations.findById(SIM))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    SIM,
                    RULE_ID,
                    2,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    List.of(),
                    SimulationStatus.RUNNING,
                    NOW,
                    null,
                    ADMIN,
                    null)));
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule(RuleStatus.INACTIVE)));
    when(events.listRecentByTrigger(anyString(), anyInt()))
        .thenReturn(
            List.of(
                new TriggerEventRow(
                    UUID.randomUUID(), "invoice_overdue", "PHARMACY", ENTITY, Map.of(), NOW)));
    when(actions.findById("send_notification")).thenReturn(Optional.empty());
    when(triggers.findById("invoice_overdue")).thenReturn(Optional.empty());
    service.processSimulation(SIM);
    verify(simulations)
        .markCompleted(eq(SIM), eq(1), eq(1), eq(0), any(), any(), any(), any(), any(), any());

    when(actions.findById("send_notification"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "send_notification",
                    "NOTIFICATION",
                    "n",
                    "d",
                    List.of(),
                    List.of(),
                    true,
                    true,
                    null)));
    doThrow(new RuntimeException())
        .when(simulations)
        .markCompleted(
            any(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
    service.processSimulation(SIM);

    // job processor when rule null after list
    when(rules.listSimulatingStartedBefore(any(), anyInt())).thenReturn(List.of(RULE_ID));
    when(rules.findById(RULE_ID)).thenReturn(Optional.empty());
    new SimulationJobProcessor(simulations, service, rules, management, notify, clock)
        .revertExpiredSimulating();
  }

  private AutomationRule rule(RuleStatus status) {
    return new AutomationRule(
        RULE_ID,
        "r",
        "d",
        "invoice_overdue",
        "FINANCE",
        Map.of(),
        List.of(),
        List.of(new ActionSpec("send_notification", Map.of(), false)),
        Guardrails.NONE,
        status,
        0,
        null,
        false,
        300,
        ADMIN,
        NOW,
        NOW);
  }
}
