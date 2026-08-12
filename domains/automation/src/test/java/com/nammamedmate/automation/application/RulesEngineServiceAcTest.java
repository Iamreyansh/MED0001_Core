package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.RulesEngineService.EvaluateCommand;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleSnapshot;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RulesEngineServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:07:00Z");

  @Mock KillSwitchPort killSwitch;
  @Mock TriggerEventStorePort events;
  @Mock RuleLookupPort rules;
  @Mock DedupPort dedup;
  @Mock ActionExecutorPort actions;
  @Mock ActivityLogPort activityLog;

  private ActiveRuleCache cache;
  private RulesEngineService service;
  private final UUID ruleId = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final UUID entityId = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private final UUID eventId = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    cache = new ActiveRuleCache(rules, clock);
    service =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            cache,
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (ruleId, max, per) -> true,
            null,
            clock);
    when(events.insert(anyString(), anyString(), any(), anyMap(), any())).thenReturn(eventId);
  }

  @Test
  void ac003_conditionsFail_noActions() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.findById(ruleId)).thenReturn(Optional.empty());
    when(rules.listActive()).thenReturn(List.of());

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(
                ruleId,
                event("order_unassigned"),
                false,
                List.of(new ConditionSpec("coverage_status", "eq", "NO_RIDERS")),
                List.of(new ActionSpec("auto_assign_rider", Map.of("order_id", entityId), false)),
                300));

    assertThat(data.get("conditions_met")).isEqualTo(false);
    assertThat((List<?>) data.get("actions_dispatched")).isEmpty();
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
    verify(events).markProcessed(eq(eventId), any(), eq(1), eq(0), eq("CONDITIONS_NOT_MET"));
  }

  @Test
  void ac004_duplicateWithinDedup_skipped() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.findById(ruleId))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    ruleId,
                    "order_unassigned",
                    List.of(),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    300)));
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(eq(ruleId), eq(entityId), eq(Duration.ofSeconds(300)))).thenReturn(true);

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(ruleId, event("order_unassigned"), false, null, null, null));

    assertThat(data.get("duplicate_skipped")).isEqualTo(true);
    assertThat(data.get("outcome")).isEqualTo("DUPLICATE_SKIPPED");
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
  }

  @Test
  void ac005_dryRun_under500ms() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(
                ruleId,
                event("order_unassigned"),
                true,
                List.of(new ConditionSpec("coverage_status", "not_eq", "NO_RIDERS")),
                List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                300));

    assertThat(data.get("conditions_met")).isEqualTo(true);
    assertThat(((Number) data.get("evaluation_ms")).longValue()).isLessThanOrEqualTo(500L);
    assertThat(((List<?>) data.get("actions_dispatched")).getFirst())
        .extracting("status")
        .isEqualTo("DRY_RUN");
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
  }

  @Test
  void ac006_killPaused_noActions() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(
                null,
                new EventPayload("order_unassigned", null, entityId, Map.of(), null),
                false,
                List.of(),
                List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                300));

    assertThat(data.get("outcome")).isEqualTo("KILL_SWITCH_PAUSED");
    assertThat((List<?>) data.get("actions_dispatched")).isEmpty();
    verify(events).markProcessed(eq(eventId), any(), eq(0), eq(0), eq("KILL_SWITCH_PAUSED"));
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
  }

  @Test
  void ac007_actionFailureContinues() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    doThrow(new RuntimeException("payout API error"))
        .when(actions)
        .execute(eq("release_payout"), anyMap(), anyMap());
    when(actions.execute(eq("send_notification"), anyMap(), anyMap()))
        .thenReturn(UUID.randomUUID());
    when(activityLog.append(eq("release_payout"), eq("EXCEPTION"), anyString(), anyMap()))
        .thenReturn(UUID.randomUUID());

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(
                ruleId,
                event("payout_due"),
                false,
                List.of(),
                List.of(
                    new ActionSpec("release_payout", Map.of("amount_paise", 100), false),
                    new ActionSpec("send_notification", Map.of("channel", "SMS"), false)),
                300));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> dispatched =
        (List<Map<String, Object>>) data.get("actions_dispatched");
    assertThat(dispatched).hasSize(2);
    assertThat(dispatched.get(0).get("status")).isEqualTo("FAILED");
    assertThat(dispatched.get(1).get("status")).isEqualTo("DISPATCHED");
    verify(actions, times(2)).execute(anyString(), anyMap(), anyMap());
  }

  @Test
  void ac008_everyEventPersisted() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());

    service.evaluate(
        new EvaluateCommand(ruleId, event("order_unassigned"), true, null, null, null));

    verify(events).insert(eq("order_unassigned"), eq("ORDER"), eq(entityId), anyMap(), any());
    verify(events).markProcessed(eq(eventId), any(), anyInt(), anyInt(), eq("NO_RULE"));
  }

  @Test
  void firesFromCacheAndRecordsDedup() {
    RuleSnapshot snap =
        new RuleSnapshot(
            ruleId,
            "order_unassigned",
            List.of(new ConditionSpec("coverage_status", "eq", "OK")),
            List.of(new ActionSpec("auto_assign_rider", Map.of("order_id", entityId), false)),
            300);
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of(snap));
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(UUID.randomUUID());
    cache.forceRefresh();

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(ruleId, event("order_unassigned"), false, null, null, null));

    assertThat(data.get("conditions_met")).isEqualTo(true);
    assertThat(data.get("outcome")).isEqualTo("RULE_FIRED");
    verify(dedup).recordFire(ruleId, entityId);
  }

  @Test
  void parallelActionDispatched() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(UUID.randomUUID());

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(
                ruleId,
                event("order_unassigned"),
                false,
                List.of(),
                List.of(
                    new ActionSpec("auto_assign_rider", Map.of(), true),
                    new ActionSpec("send_notification", Map.of(), true)),
                300));

    assertThat((List<?>) data.get("actions_dispatched")).hasSize(2);
  }

  @Test
  void invalidEventThrows() {
    assertThatThrownBy(() -> service.evaluate(null))
        .isInstanceOf(AppException.class)
        .hasMessageContaining("trigger_id");
  }

  @Test
  void publicConstructorAndNullEventFields() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RulesEngineService svc =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            cache,
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (ruleId, max, per) -> true,
            null,
            clock);
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    Map<String, Object> data =
        svc.evaluate(
            new EvaluateCommand(
                null,
                new EventPayload("order_placed", null, null, null, null),
                true,
                List.of(),
                List.of(),
                0));
    assertThat(data.get("outcome")).isEqualTo("RULE_FIRED");
  }

  @Test
  void actionFailureNullMessage() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    doThrow(new RuntimeException()).when(actions).execute(anyString(), anyMap(), anyMap());
    when(activityLog.append(anyString(), eq("EXCEPTION"), anyString(), anyMap()))
        .thenReturn(UUID.randomUUID());

    Map<String, Object> data =
        service.evaluate(
            new EvaluateCommand(
                ruleId,
                event("order_unassigned"),
                false,
                List.of(),
                List.of(new ActionSpec("auto_assign_rider", null, false)),
                null));
    assertThat(((List<?>) data.get("actions_dispatched")).getFirst())
        .extracting("status")
        .isEqualTo("FAILED");
  }

  @Test
  void rateLimitedAndSimulating() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(rules.findById(ruleId))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    ruleId,
                    "order_unassigned",
                    List.of(new ConditionSpec("coverage_status", "eq", "OK")),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    300,
                    RuleStatus.ACTIVE,
                    new Guardrails(new Guardrails.RateLimit(1, 60), null, null))));
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);

    RulesEngineService limited =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            cache,
            dedup,
            new ConditionEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)),
            actions,
            activityLog,
            (id, max, per) -> false,
            null,
            Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> limitedOut =
        limited.evaluate(
            new EvaluateCommand(ruleId, event("order_unassigned"), false, null, null, null));
    assertThat(limitedOut.get("outcome")).isEqualTo("RATE_LIMITED");

    when(rules.findById(ruleId))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    ruleId,
                    "order_unassigned",
                    List.of(),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    300,
                    RuleStatus.SIMULATING,
                    Guardrails.NONE)));
    when(activityLog.append(eq("auto_assign_rider"), eq("SIMULATED"), anyString(), anyMap()))
        .thenReturn(UUID.randomUUID());
    Map<String, Object> sim =
        service.evaluate(
            new EvaluateCommand(ruleId, event("order_unassigned"), false, null, null, null));
    assertThat(sim.get("outcome")).isEqualTo("SIMULATED");
    verify(activityLog).append(eq("auto_assign_rider"), eq("SIMULATED"), anyString(), anyMap());
  }

  @Test
  void routesValueCapAndRequireApprovalToQueue() {
    ApprovalQueueService queue = org.mockito.Mockito.mock(ApprovalQueueService.class);
    ActionRegistryPort registry = org.mockito.Mockito.mock(ActionRegistryPort.class);
    RuleStorePort store = org.mockito.Mockito.mock(RuleStorePort.class);
    UUID approvalId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    when(queue.enqueue(any())).thenReturn(approvalId);
    when(registry.findById("release_payout"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "release_payout",
                    "FINANCE",
                    "n",
                    "d",
                    List.of(),
                    List.of(),
                    false,
                    false,
                    5_000_000L)));
    RuleSnapshot snap =
        new RuleSnapshot(
            ruleId,
            "payout_due",
            List.of(new ConditionSpec("coverage_status", "eq", "OK")),
            List.of(
                new ActionSpec(
                    "release_payout",
                    Map.of("amount_paise", 4_800_000L, "entity_name", "Apollo"),
                    false)),
            300,
            RuleStatus.ACTIVE,
            new Guardrails(null, 4_000_000L, null, false, "open_csm_task"));
    when(rules.findById(ruleId)).thenReturn(Optional.of(snap));
    when(store.findById(ruleId))
        .thenReturn(
            Optional.of(
                new AutomationRule(
                    ruleId,
                    "Auto-release due payouts",
                    "d",
                    "payout_due",
                    "FINANCE",
                    Map.of(),
                    List.of(),
                    snap.actions(),
                    snap.guardrails(),
                    RuleStatus.ACTIVE,
                    0,
                    null,
                    false,
                    300,
                    null,
                    NOW,
                    NOW)));
    RulesEngineService svc =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            cache,
            dedup,
            new ConditionEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)),
            actions,
            activityLog,
            (id, max, per) -> true,
            store,
            queue,
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);

    Map<String, Object> data =
        svc.evaluate(
            new EvaluateCommand(
                ruleId,
                new EventPayload(
                    "payout_due",
                    "PHARMACY",
                    entityId,
                    Map.of("amount_paise", 4_800_000L, "coverage_status", "OK", "sla_breach", true),
                    NOW),
                false,
                null,
                null,
                null));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> dispatched =
        (List<Map<String, Object>>) data.get("actions_dispatched");
    assertThat(dispatched.getFirst().get("status")).isEqualTo("PENDING_APPROVAL");
    assertThat(dispatched.getFirst().get("approval_id")).isEqualTo(approvalId);
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
    verify(queue).enqueue(any());

    when(rules.findById(ruleId))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    ruleId,
                    "order_unassigned",
                    List.of(),
                    List.of(new ActionSpec("suspend_entity", Map.of("entity_count", 9), false)),
                    300,
                    RuleStatus.ACTIVE,
                    new Guardrails(null, null, null, true, null))));
    when(store.findById(ruleId)).thenReturn(Optional.empty());
    RulesEngineService noReg =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            cache,
            dedup,
            new ConditionEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)),
            actions,
            activityLog,
            (id, max, per) -> true,
            store,
            queue,
            null,
            Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> flagged =
        noReg.evaluate(
            new EvaluateCommand(
                ruleId,
                new EventPayload("order_unassigned", "RIDER", entityId, Map.of(), NOW),
                false,
                null,
                null,
                null));
    assertThat(((List<?>) flagged.get("actions_dispatched")).getFirst())
        .extracting("status")
        .isEqualTo("PENDING_APPROVAL");
  }

  private EventPayload event(String triggerId) {
    return new EventPayload(
        triggerId,
        "ORDER",
        entityId,
        Map.of("coverage_status", "OK", "order_id", entityId.toString()),
        NOW);
  }
}
