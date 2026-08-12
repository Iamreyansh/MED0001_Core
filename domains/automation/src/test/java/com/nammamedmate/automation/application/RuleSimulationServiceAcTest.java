package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RateLimitPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.application.port.out.SimulationNotifyPort;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.application.port.out.TriggerEventQueryPort;
import com.nammamedmate.automation.application.port.out.TriggerEventQueryPort.TriggerEventRow;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleSnapshot;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.SimulationStatus;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleSimulationServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:30:00Z");
  private static final UUID RULE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID SIM_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Mock RuleStorePort rules;
  @Mock SimulationStorePort simulations;
  @Mock TriggerEventQueryPort events;
  @Mock TriggerRegistryPort triggers;
  @Mock ActionRegistryPort actions;
  @Mock DedupPort dedup;
  @Mock RateLimitPort rateLimit;
  @Mock KillSwitchPort killSwitch;
  @Mock TriggerEventStorePort eventStore;
  @Mock RuleLookupPort ruleLookup;
  @Mock ActionExecutorPort actionExecutor;
  @Mock ActivityLogPort activityLog;
  @Mock SimulationNotifyPort notify;
  @Mock RuleStorePort ruleStoreForEngine;

  private RuleSimulationService service;
  private RulesEngineService engine;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ConditionEvaluator evaluator = new ConditionEvaluator(clock);
    service =
        new RuleSimulationService(
            rules,
            simulations,
            events,
            triggers,
            actions,
            evaluator,
            dedup,
            rateLimit,
            activityLog,
            clock);
    engine =
        new RulesEngineService(
            killSwitch,
            eventStore,
            ruleLookup,
            new ActiveRuleCache(ruleLookup, clock),
            dedup,
            evaluator,
            actionExecutor,
            activityLog,
            rateLimit,
            ruleStoreForEngine,
            clock);
    when(triggers.findById("invoice_overdue"))
        .thenReturn(
            Optional.of(
                new TriggerDefinition(
                    "invoice_overdue",
                    "FINANCE",
                    "Invoice Overdue",
                    "d",
                    List.of(),
                    List.of("eq", "amount_gt", "plan_tier_eq"),
                    List.of("invoice.id", "pharmacy.id", "invoice.status"),
                    true)));
    when(triggers.findById("order_unassigned"))
        .thenReturn(
            Optional.of(
                new TriggerDefinition(
                    "order_unassigned",
                    "DISPATCH",
                    "Order Unassigned",
                    "d",
                    List.of(),
                    List.of("zone_in"),
                    List.of("order.id", "order.zone_id"),
                    true)));
  }

  @Test
  void ac001_simulateReturns202ShapeAndProcessCompletes() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(inactiveRule("send_notification")));
    AtomicReference<AutomationSimulation> stored = new AtomicReference<>();
    doAnswer(
            inv -> {
              stored.set(inv.getArgument(0));
              return null;
            })
        .when(simulations)
        .insert(any());
    when(simulations.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
    when(simulations.findByRuleAndId(eq(RULE_ID), any()))
        .thenAnswer(inv -> Optional.ofNullable(stored.get()));
    when(events.listRecentByTrigger(eq("invoice_overdue"), anyInt()))
        .thenReturn(
            List.of(
                new TriggerEventRow(
                    UUID.randomUUID(),
                    "invoice_overdue",
                    "PHARMACY",
                    ENTITY_ID,
                    Map.of("invoice.status", "OVERDUE", "entity_name", "Medplus"),
                    NOW)));
    when(actions.findById("send_notification"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "send_notification",
                    "NOTIFICATION",
                    "Send",
                    "d",
                    List.of(),
                    List.of(),
                    true,
                    false,
                    null)));

    Map<String, Object> started = service.startBatch(admin, RULE_ID, 100, true);
    assertThat(started.get("status")).isEqualTo("RUNNING");
    assertThat(started.get("simulation_id")).isNotNull();

    UUID simId = (UUID) started.get("simulation_id");
    when(simulations.findById(simId)).thenReturn(Optional.of(stored.get()));
    service.processSimulation(simId);

    ArgumentCaptor<FalsePositiveRisk> riskCap = ArgumentCaptor.forClass(FalsePositiveRisk.class);
    verify(simulations)
        .markCompleted(
            eq(simId), eq(1), eq(1), eq(0), riskCap.capture(), any(), any(), any(), any(), any());
    assertThat(riskCap.getValue()).isEqualTo(FalsePositiveRisk.LOW);

    when(simulations.findByRuleAndId(RULE_ID, simId))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    simId,
                    RULE_ID,
                    100,
                    1,
                    1,
                    0,
                    FalsePositiveRisk.LOW,
                    "ok",
                    "Would have fired 1 times in the last 7 days, affecting 1 pharmacy, executing send_notification for 1 pharmacy.",
                    List.of(),
                    SimulationStatus.COMPLETED,
                    NOW,
                    NOW,
                    ADMIN_ID,
                    NOW.plus(Duration.ofDays(7)))));
    Map<String, Object> results = service.getResults(admin, RULE_ID, simId);
    assertThat(results.get("status")).isEqualTo("COMPLETED");
    assertThat(results.get("estimated_impact_summary").toString()).contains("Would have fired");
  }

  @Test
  void ac002_activeRuleRejected() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(activeRule()));
    assertThatThrownBy(() -> service.startBatch(admin, RULE_ID, 100, true))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("RULE_IS_ACTIVE");
              assertThat(ae.httpStatus()).isEqualTo(422);
            });
  }

  @Test
  void ac003_highRiskWhenSuspendAffectsOver10Percent() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(inactiveRule("suspend_entity")));
    List<TriggerEventRow> rows = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      rows.add(
          new TriggerEventRow(
              UUID.randomUUID(),
              "invoice_overdue",
              "PHARMACY",
              UUID.randomUUID(),
              Map.of("invoice.status", "OVERDUE"),
              NOW));
    }
    AutomationSimulation running =
        new AutomationSimulation(
            SIM_ID,
            RULE_ID,
            10,
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
            ADMIN_ID,
            null);
    when(simulations.findById(SIM_ID)).thenReturn(Optional.of(running));
    when(events.listRecentByTrigger("invoice_overdue", 10)).thenReturn(rows);
    when(actions.findById("suspend_entity"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "suspend_entity",
                    "ADMIN",
                    "Suspend",
                    "d",
                    List.of(),
                    List.of(),
                    true,
                    true,
                    null)));

    service.processSimulation(SIM_ID);

    ArgumentCaptor<FalsePositiveRisk> risk = ArgumentCaptor.forClass(FalsePositiveRisk.class);
    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(simulations)
        .markCompleted(
            eq(SIM_ID),
            eq(10),
            eq(10),
            eq(0),
            risk.capture(),
            any(),
            summary.capture(),
            any(),
            any(),
            any());
    assertThat(risk.getValue()).isEqualTo(FalsePositiveRisk.HIGH);
    assertThat(summary.getValue()).contains("Would have fired");
  }

  @Test
  void ac004_getResultsWhileRunning() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(inactiveRule("send_notification")));
    when(simulations.findByRuleAndId(RULE_ID, SIM_ID))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    SIM_ID,
                    RULE_ID,
                    100,
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
                    ADMIN_ID,
                    null)));
    Map<String, Object> body = service.getResults(admin, RULE_ID, SIM_ID);
    assertThat(body.get("status")).isEqualTo("RUNNING");
    assertThat(body).doesNotContainKey("events_scanned");
  }

  @Test
  void ac005_previewWouldFireTrue() {
    when(rules.findById(RULE_ID))
        .thenReturn(
            Optional.of(
                ruleWithConditions(
                    List.of(new ConditionSpec("invoice.status", "eq", "OVERDUE")),
                    "send_notification")));
    when(events.findLatestByEntity("PHARMACY", ENTITY_ID))
        .thenReturn(
            Optional.of(
                new TriggerEventRow(
                    UUID.randomUUID(),
                    "invoice_overdue",
                    "PHARMACY",
                    ENTITY_ID,
                    Map.of("invoice.status", "OVERDUE", "entity_name", "Medplus - HSR Layout"),
                    NOW)));
    when(actions.findById("send_notification"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "send_notification",
                    "NOTIFICATION",
                    "Send",
                    "d",
                    List.of(),
                    List.of(),
                    true,
                    false,
                    null)));
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);

    Map<String, Object> data = service.preview(admin, RULE_ID, "PHARMACY", ENTITY_ID);
    assertThat(data.get("would_fire")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> conds = (List<Map<String, Object>>) data.get("conditions_evaluated");
    assertThat(conds.getFirst().get("resolved_value")).isEqualTo("OVERDUE");
    assertThat(conds.getFirst().get("result")).isEqualTo(true);
  }

  @Test
  void ac006_previewWouldFireFalse() {
    when(rules.findById(RULE_ID))
        .thenReturn(
            Optional.of(
                ruleWithConditions(
                    List.of(new ConditionSpec("invoice.status", "eq", "OVERDUE")),
                    "send_notification")));
    when(events.findLatestByEntity("PHARMACY", ENTITY_ID))
        .thenReturn(
            Optional.of(
                new TriggerEventRow(
                    UUID.randomUUID(),
                    "invoice_overdue",
                    "PHARMACY",
                    ENTITY_ID,
                    Map.of("invoice.status", "PAID"),
                    NOW)));
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);

    Map<String, Object> data = service.preview(admin, RULE_ID, "PHARMACY", ENTITY_ID);
    assertThat(data.get("would_fire")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> conds = (List<Map<String, Object>>) data.get("conditions_evaluated");
    assertThat(conds.getFirst().get("result")).isEqualTo(false);
    assertThat(conds.getFirst().get("resolved_value")).isEqualTo("PAID");
  }

  @Test
  void ac007_simulatingWritesActivityNoRealActions() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(eventStore.insert(anyString(), anyString(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());
    when(ruleLookup.findById(RULE_ID))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    RULE_ID,
                    "invoice_overdue",
                    List.of(),
                    List.of(new ActionSpec("send_notification", Map.of("t", "1"), false)),
                    300,
                    RuleStatus.SIMULATING,
                    Guardrails.NONE)));
    when(ruleLookup.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(activityLog.append(anyString(), eq("SIMULATED"), anyString(), any()))
        .thenReturn(UUID.randomUUID());

    Map<String, Object> out =
        engine.evaluate(
            new RulesEngineService.EvaluateCommand(
                RULE_ID,
                new RulesEngineService.EventPayload(
                    "invoice_overdue", "PHARMACY", ENTITY_ID, Map.of(), NOW),
                false,
                null,
                null,
                null));
    assertThat(out.get("outcome")).isEqualTo("SIMULATED");
    verify(actionExecutor, never()).execute(anyString(), any(), any());
    verify(activityLog).append(eq("send_notification"), eq("SIMULATED"), anyString(), any());
  }

  @Test
  void ac008_autoRevertSimulatingNotifies() {
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
            ADMIN_ID,
            NOW.minus(Duration.ofHours(25)),
            NOW.minus(Duration.ofHours(25)));
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(simulating));
    when(rules.listSimulatingStartedBefore(any(), anyInt())).thenReturn(List.of(RULE_ID));

    // RuleManagementService needs more deps — exercise via SimulationJobProcessor + real
    // RuleManagementService with mocks
    var audit =
        org.mockito.Mockito.mock(
            com.nammamedmate.automation.application.port.out.RuleAuditPort.class);
    var cache = new ActiveRuleCache(ruleLookup, clock);
    RuleManagementService mgmt =
        new RuleManagementService(
            rules,
            triggers,
            actions,
            audit,
            cache,
            org.mockito.Mockito.mock(SeedCatalogPort.class),
            clock);
    SimulationJobProcessor job =
        new SimulationJobProcessor(simulations, service, rules, mgmt, notify, clock);
    job.revertExpiredSimulating();
    verify(rules).clearSimulatingStarted(RULE_ID);
    verify(notify).simulatingAutoReverted(eq(RULE_ID), eq(ADMIN_ID), eq("r"));
  }

  @Test
  void ac009_impactSummaryPresent() {
    ac003_highRiskWhenSuspendAffectsOver10Percent();
  }

  @Test
  void sampleSizeTooLarge() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(inactiveRule("send_notification")));
    assertThatThrownBy(() -> service.startBatch(admin, RULE_ID, 1001, true))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SAMPLE_SIZE_TOO_LARGE");
  }

  @Test
  void invalidEntityTypeAndNotFound() {
    when(rules.findById(RULE_ID)).thenReturn(Optional.of(inactiveRule("send_notification")));
    assertThatThrownBy(() -> service.preview(admin, RULE_ID, "RIDER", ENTITY_ID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ENTITY_TYPE");
    assertThatThrownBy(() -> service.preview(admin, RULE_ID, "PHARMACY", ENTITY_ID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ENTITY_NOT_FOUND");
  }

  private AutomationRule inactiveRule(String actionId) {
    return ruleWithConditions(List.of(), actionId);
  }

  private AutomationRule activeRule() {
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
        RuleStatus.ACTIVE,
        0,
        null,
        false,
        300,
        ADMIN_ID,
        NOW,
        NOW);
  }

  private AutomationRule ruleWithConditions(List<ConditionSpec> conditions, String actionId) {
    return new AutomationRule(
        RULE_ID,
        "r",
        "d",
        "invoice_overdue",
        "FINANCE",
        Map.of(),
        conditions,
        List.of(new ActionSpec(actionId, Map.of("template", "X"), false)),
        Guardrails.NONE,
        RuleStatus.INACTIVE,
        0,
        null,
        false,
        300,
        ADMIN_ID,
        NOW,
        NOW);
  }
}
