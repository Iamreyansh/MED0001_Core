package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.ApprovalQueueService;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.RulesEngineService.EvaluateCommand;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ApprovalNotifyPort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.ApprovalQueueStats;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.Chips;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalRouter;
import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleSnapshot;
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
class CoverageStory006GapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:45:00Z");
  private static final UUID ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Mock ApprovalStorePort store;
  @Mock ApprovalNotifyPort notify;
  @Mock ActionExecutorPort actions;
  @Mock ActivityLogPort activityLog;
  @Mock KillSwitchPort killSwitch;
  @Mock TriggerEventStorePort events;
  @Mock RuleLookupPort rules;
  @Mock DedupPort dedup;
  @Mock ApprovalQueueService queue;

  @Test
  void routerRemainingBranches() {
    assertThat(
            ApprovalRouter.requiresApproval(
                "release_payout", Map.of("amount_paise", 100L), Map.of(), null, null))
        .isFalse();
    assertThat(
            ApprovalRouter.requiresApproval(
                "release_payout",
                Map.of("amount_paise", 5_000_000L),
                Map.of(),
                new Guardrails(null, 5_000_000L, 5_000_000L),
                null))
        .isFalse();
    assertThat(ApprovalRouter.requiresApproval(null, Map.of(), Map.of(), Guardrails.NONE, null))
        .isFalse();
    assertThat(
            ApprovalRouter.requiresApproval(
                "release_payout",
                Map.of("amount_paise", 10_000_000L),
                Map.of(),
                Guardrails.NONE,
                null))
        .isFalse();
    assertThat(ApprovalRouter.extractAmount(Map.of(), Map.of("amount_paise", 9L))).isEqualTo(9L);
    assertThat(ApprovalRouter.extractAmount(Map.of(), Map.of("payload", "x"))).isNull();
    assertThat(ApprovalRouter.isMassSuspension("other", Map.of(), Map.of())).isFalse();
    assertThat(
            ApprovalRouter.isMassSuspension(
                "suspend_entity", Map.of("entity_ids", List.of(1, 2, 3, 4, 5)), Map.of()))
        .isFalse();
    assertThat(ApprovalRouter.isPayoutAction(null)).isFalse();
    assertThat(
            ApprovalRouter.isSlaBreach(Map.of("sla_breach", false, "trigger_id", "order_placed")))
        .isFalse();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("payload", "nope"))).isFalse();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("payload", Map.of("sla_breach", false))))
        .isFalse();
    assertThat(ApprovalRouter.why(null, null, Map.of(), Map.of(), null, null))
        .contains("human approval");
    assertThat(ApprovalRouter.estimatedImpact("release_payout", null, "  ", null))
        .contains("Execute");
    assertThat(ApprovalRouter.estimatedImpact("mass_suspension", null, "", 1L)).contains("Suspend");
    assertThat(ApprovalRouter.estimatedImpact(null, "T", "n", null)).contains("action");
    assertThat(ApprovalRouter.extractAmount(Map.of("amount_paise", ""), Map.of())).isNull();
    assertThat(ApprovalRouter.extractAmount(Map.of("amount", "null"), Map.of())).isNull();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("sla_breach", Boolean.FALSE))).isFalse();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("sla_breached", "no"))).isFalse();
    assertThat(ApprovalCategory.fromAction("x", " ")).isEqualTo(ApprovalCategory.ADMIN);
    assertThat(ApprovalCategory.fromAction("x", "DISPATCH")).isEqualTo(ApprovalCategory.ADMIN);
    assertThatThrownBy(() -> ApprovalStatus.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalStatus.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalUrgency.parse(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new AutomationApproval(
                    ID, null, null, null, null, "", Map.of(), "  ", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, NOW, NOW,
                    null)
                .entityType())
        .isEqualTo("UNKNOWN");
    assertThat(new ApprovalQueueStats(0, 0, 0, 0, null).topPendingCategories()).isEmpty();
    assertThat(Guardrails.fromMap(Map.of("on_reject_action", "null")).onRejectAction()).isNull();
    assertThat(ApprovalCategory.fromAction("change_plan", null)).isEqualTo(ApprovalCategory.CRM);
    assertThat(ApprovalRouter.isMassSuspension("mass_suspension", Map.of(), Map.of())).isTrue();
    assertThat(ApprovalRouter.isMassSuspension("suspend_entity", Map.of("count", 9), Map.of()))
        .isTrue();
    assertThat(
            ApprovalRouter.isSlaBreach(
                Map.of("trigger_id", "order_placed", "payload", Map.of("sla_breached", true))))
        .isTrue();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("trigger_event", "hello"))).isFalse();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("sla_breach", "true"))).isTrue();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("sla_breached", true))).isTrue();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("payload", Map.of("sla_breach", true)))).isTrue();
    assertThat(
            ApprovalRouter.why(
                "release_payout",
                100L,
                Map.of(),
                Map.of(),
                new Guardrails(null, 1000L, 1000L),
                new ActionDefinition(
                    "release_payout", "FINANCE", "n", "d", null, null, false, false, null)))
        .contains("human approval");
    assertThat(
            ApprovalRouter.requiresApproval(
                "release_payout",
                Map.of("amount_paise", 100L),
                Map.of(),
                new Guardrails(null, 1000L, null),
                null))
        .isFalse();
    assertThat(
            ApprovalRouter.requiresApproval(
                "auto_assign_rider",
                Map.of("amount_paise", 11_000_000L),
                Map.of(),
                Guardrails.NONE,
                null))
        .isFalse();
    assertThat(
            ApprovalRouter.why(
                "auto_assign_rider", 11_000_000L, Map.of(), Map.of(), Guardrails.NONE, null))
        .contains("human approval");
  }

  @Test
  void queueSparseApproveRejectAndList() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ApprovalQueueService service =
        new ApprovalQueueService(store, notify, actions, activityLog, clock, Duration.ofHours(4));
    MedmatePrincipal ops =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    AutomationApproval sparse =
        new AutomationApproval(
            ID,
            null,
            null,
            null,
            null,
            "open_csm_task",
            Map.of(),
            "PHARMACY",
            null,
            null,
            null,
            ApprovalCategory.CRM,
            ApprovalUrgency.NORMAL,
            "w",
            Map.of(),
            List.of(),
            "i",
            "  ",
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW.plus(Duration.ofHours(4)),
            null);
    when(store.findPending(any(), any(), any())).thenReturn(Optional.empty());
    when(activityLog.append(anyString(), anyString(), anyString(), anyMap())).thenReturn(ID);
    service.enqueue(
        new ApprovalQueueService.EnqueueCommand(
            null,
            null,
            null,
            null,
            "open_csm_task",
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            null,
            null,
            null));
    service.enqueue(
        new ApprovalQueueService.EnqueueCommand(
            ID, "r", null, null, "x", Map.of(), "E", null, "n", null, null, null, "w", Map.of(),
            List.of(), null, null, null));
    service.enqueue(
        new ApprovalQueueService.EnqueueCommand(
            ID, "r", null, null, null, Map.of(), "E", ID, "n", null, null, null, "w", Map.of(),
            List.of(), null, null, null));
    when(store.findById(ID)).thenReturn(Optional.of(sparse));
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(ID);
    when(store.markResolved(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(service.approve(ops, ID, null).get("action_executed")).isEqualTo(true);
    assertThat(service.reject(ops, ID, "nope").get("alternative_action_fired")).isEqualTo(false);

    AutomationApproval noExpiry =
        new AutomationApproval(
            ID,
            null,
            null,
            null,
            null,
            "x",
            Map.of(),
            "E",
            null,
            null,
            null,
            ApprovalCategory.ADMIN,
            ApprovalUrgency.NORMAL,
            "w",
            Map.of(),
            List.of(),
            "i",
            null,
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(store.findById(ID)).thenReturn(Optional.of(noExpiry));
    assertThat(service.approve(ops, ID, "n").get("status")).isEqualTo("APPROVED");

    when(store.findById(ID)).thenReturn(Optional.of(sparse));
    when(store.chips(NOW)).thenReturn(new Chips(0, 0, 0, 0));
    when(store.count(any(), any())).thenReturn(1L);
    when(store.list(any(), any(), anyInt(), anyInt())).thenReturn(List.of(sparse));
    when(store.stats(NOW)).thenReturn(new ApprovalQueueStats(1, 2, 3, 4, List.of()));
    service.list(ops, "PENDING", null, 1, null);
    service.list(ops, "PENDING", null, 1, 0);
    ApprovalQueueService.PagedResult page = service.list(ops, "  ", "  ", 0, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.data().get("approvals");
    assertThat(items.getFirst().get("rule_name")).isEqualTo("");
    assertThat(items.getFirst().get("amount_rs")).isNull();
    assertThat(service.get(ops, ID).get("rule_name")).isEqualTo("");
    assertThat(service.stats(ops).get("approval_rate_pct")).isEqualTo(2.0);
  }

  @Test
  void engineExecutesWhenQueuePresentButNotRequired() {
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
            new com.nammamedmate.automation.application.ActiveRuleCache(rules, clock),
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (a, b, c) -> true,
            null,
            queue,
            null,
            clock);
    Map<String, Object> data =
        svc.evaluate(
            new EvaluateCommand(
                ID,
                new EventPayload(
                    "order_unassigned", "ORDER", ID, Map.of("entity_name", "from-ctx"), NOW),
                false,
                List.of(),
                List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                300));
    assertThat(((List<?>) data.get("actions_dispatched")).getFirst())
        .extracting("status")
        .isEqualTo("DISPATCHED");
    verify(actions).execute(eq("auto_assign_rider"), anyMap(), anyMap());
  }

  @Test
  void engineEnqueueUsesContextEntityName() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(events.insert(anyString(), anyString(), any(), anyMap(), any())).thenReturn(ID);
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(queue.enqueue(any())).thenReturn(ID);
    when(rules.findById(ID))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    ID,
                    "t",
                    List.of(),
                    List.of(new ActionSpec("suspend_entity", Map.of(), false)),
                    300,
                    com.nammamedmate.automation.domain.RuleStatus.ACTIVE,
                    new Guardrails(null, null, null, true, null))));
    RulesEngineService svc =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            new com.nammamedmate.automation.application.ActiveRuleCache(rules, clock),
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (a, b, c) -> true,
            null,
            queue,
            null,
            clock);
    svc.evaluate(
        new EvaluateCommand(
            ID,
            new EventPayload("t", "RIDER", ID, Map.of("entity_name", "Suresh"), NOW),
            false,
            null,
            null,
            null));
    verify(queue).enqueue(any());
  }

  @Test
  void routerDefAlwaysFalseAndAmountFromContextPayload() {
    ActionDefinition def =
        new ActionDefinition("x", "ADMIN", "n", "d", List.of(), List.of(), false, false, null);
    assertThat(ApprovalRouter.requiresApproval("x", Map.of(), Map.of(), Guardrails.NONE, def))
        .isFalse();
    assertThat(ApprovalRouter.why("x", 1L, Map.of(), Map.of(), Guardrails.NONE, def))
        .contains("human");
    assertThat(
            ApprovalRouter.extractAmount(
                Map.of(), Map.of("payload", Map.of("payout_amount_paise", 12))))
        .isEqualTo(12L);
  }
}
