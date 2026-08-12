package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.ActiveRuleCache;
import com.nammamedmate.automation.application.ApprovalQueueService;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.RulesEngineService.EvaluateCommand;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ApprovalNotifyPort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.CircuitBreakerPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.DeferredExecutionPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.DeferredExecution;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoverageStory007Test {

  private static final Instant NOW = Instant.parse("2026-07-24T09:50:00Z");
  private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Mock KillSwitchPort killSwitch;
  @Mock TriggerEventStorePort events;
  @Mock RuleLookupPort rules;
  @Mock DedupPort dedup;
  @Mock ActionExecutorPort actions;
  @Mock ActivityLogPort activityLog;
  @Mock CircuitBreakerPort circuits;
  @Mock ApprovalStorePort store;
  @Mock ApprovalNotifyPort notify;
  @Mock DeferredExecutionPort deferred;

  @Test
  void ac006_circuitOpenLogsException() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(events.insert(anyString(), anyString(), any(), anyMap(), any())).thenReturn(ID);
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(circuits.tryAcquire("apply_wallet_credit")).thenReturn(false);
    when(activityLog.append(
            eq("apply_wallet_credit"), eq("EXCEPTION"), eq("CIRCUIT_OPEN"), anyMap()))
        .thenReturn(ID);
    RulesEngineService svc =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            new ActiveRuleCache(rules, clock),
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (a, b, c) -> true,
            null,
            null,
            null,
            circuits,
            clock);
    Map<String, Object> data =
        svc.evaluate(
            new EvaluateCommand(
                ID,
                new EventPayload("payment_failed", "ORDER", ID, Map.of(), NOW),
                false,
                List.of(),
                List.of(new ActionSpec("apply_wallet_credit", Map.of("amount_paise", 1), false)),
                300));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> dispatched =
        (List<Map<String, Object>>) data.get("actions_dispatched");
    assertThat(dispatched.getFirst().get("status")).isEqualTo("FAILED");
    verify(activityLog)
        .append(eq("apply_wallet_credit"), eq("EXCEPTION"), eq("CIRCUIT_OPEN"), anyMap());
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
  }

  @Test
  void flushDeferredAndRejectWhilePaused() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ApprovalQueueService service =
        new ApprovalQueueService(
            store, notify, actions, activityLog, clock, Duration.ofHours(4), killSwitch, deferred);
    MedmatePrincipal ops =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);
    AutomationApproval pending =
        new AutomationApproval(
            ID,
            ID,
            "r",
            null,
            "t",
            "release_payout",
            Map.of(),
            "PHARMACY",
            ID,
            "n",
            1L,
            ApprovalCategory.FINANCE,
            ApprovalUrgency.NORMAL,
            "w",
            Map.of(),
            List.of(),
            "i",
            "open_csm_task",
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW.plus(Duration.ofHours(1)),
            null);
    when(store.findById(ID)).thenReturn(Optional.of(pending));
    when(activityLog.append(anyString(), anyString(), anyString(), anyMap())).thenReturn(ID);
    when(store.markResolved(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(service.reject(ops, ID, "no").get("alternative_action_fired")).isEqualTo(false);
    verify(deferred).enqueue(eq(ID), eq("open_csm_task"), anyMap(), anyMap());
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());

    UUID defId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    when(deferred.listAll())
        .thenReturn(
            List.of(
                new DeferredExecution(defId, ID, "open_csm_task", Map.of(), Map.of(), NOW),
                new DeferredExecution(ID, ID, "bad", Map.of(), Map.of(), NOW)));
    doThrow(new RuntimeException("x")).when(actions).execute(eq("bad"), anyMap(), anyMap());
    when(actions.execute(eq("open_csm_task"), anyMap(), anyMap())).thenReturn(ID);
    assertThat(service.flushDeferred()).isEqualTo(1);
    verify(deferred).delete(defId);
    verify(deferred).delete(ID);

    ApprovalQueueService noDeferred =
        new ApprovalQueueService(store, notify, actions, activityLog, clock);
    assertThat(noDeferred.flushDeferred()).isZero();

    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    ApprovalQueueService activeSwitch =
        new ApprovalQueueService(
            store, notify, actions, activityLog, clock, Duration.ofHours(4), killSwitch, deferred);
    when(store.findById(ID)).thenReturn(Optional.of(pending));
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(ID);
    assertThat(activeSwitch.approve(ops, ID, "go").get("action_executed")).isEqualTo(true);

    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);
    ApprovalQueueService pausedNoQueue =
        new ApprovalQueueService(
            store, notify, actions, activityLog, clock, Duration.ofHours(4), killSwitch, null);
    when(store.findById(ID)).thenReturn(Optional.of(pending));
    assertThat(pausedNoQueue.approve(ops, ID, "n").get("action_executed")).isEqualTo(false);
    assertThat(pausedNoQueue.reject(ops, ID, "still no").get("alternative_action_fired"))
        .isEqualTo(false);
  }

  @Test
  void engineThirteenArgStillWorks() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(events.insert(anyString(), anyString(), any(), anyMap(), any())).thenReturn(ID);
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(ID);
    RulesEngineService svc =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            new ActiveRuleCache(rules, clock),
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (a, b, c) -> true,
            null,
            null,
            null,
            clock);
    svc.evaluate(
        new EvaluateCommand(
            ID,
            new EventPayload("order_unassigned", "ORDER", ID, Map.of(), NOW),
            false,
            List.of(),
            List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
            300));
    verify(actions).execute(eq("auto_assign_rider"), anyMap(), anyMap());

    when(circuits.tryAcquire(anyString())).thenReturn(true);
    RulesEngineService withCircuit =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            new ActiveRuleCache(rules, clock),
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (a, b, c) -> true,
            null,
            null,
            null,
            circuits,
            clock);
    withCircuit.evaluate(
        new EvaluateCommand(
            ID,
            new EventPayload("order_unassigned", "ORDER", ID, Map.of(), NOW),
            false,
            List.of(),
            List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
            300));
    verify(circuits).tryAcquire("auto_assign_rider");
  }
}
