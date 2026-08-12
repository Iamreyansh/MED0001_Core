package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ApprovalNotifyPort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.CircuitBreakerPort;
import com.nammamedmate.automation.application.port.out.DeferredExecutionPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
import com.nammamedmate.automation.domain.CircuitBreakerState;
import com.nammamedmate.automation.domain.CircuitStatus;
import com.nammamedmate.automation.domain.DeferredExecution;
import com.nammamedmate.automation.domain.KillSwitchAction;
import com.nammamedmate.automation.domain.KillSwitchChange;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleHealthMetrics;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.kernel.error.AppException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutomationHealthServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:50:00Z");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID RULE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID APPR = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACT = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Mock KillSwitchPort killSwitch;
  @Mock ActivityLogPort activity;
  @Mock RuleStorePort rules;
  @Mock ApprovalStorePort approvals;
  @Mock CircuitBreakerPort circuits;
  @Mock ApprovalNotifyPort notify;
  @Mock ActionExecutorPort actions;
  @Mock DeferredExecutionPort deferred;

  private AutomationHealthService health;
  private ApprovalQueueService queue;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal ops;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    queue =
        new ApprovalQueueService(
            approvals, notify, actions, activity, clock, Duration.ofHours(4), killSwitch, deferred);
    health =
        new AutomationHealthService(killSwitch, activity, rules, approvals, circuits, queue, clock);
    superAdmin = principal(AuthRole.ADMIN_SUPER);
    ops = principal(AuthRole.ADMIN_OPERATIONS);
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(killSwitch.lastChange()).thenReturn(Optional.empty());
    when(rules.countByStatus(RuleStatus.ACTIVE)).thenReturn(8L);
    when(rules.countByStatus(RuleStatus.SIMULATING)).thenReturn(1L);
    when(rules.countByStatus(RuleStatus.INACTIVE)).thenReturn(3L);
    when(approvals.countPending()).thenReturn(3L);
    when(activity.stats(any())).thenReturn(new ActivityStats(284, 1842, 18420, 2, 3, NOW));
  }

  @Test
  void ac001_pauseHaltsImmediately() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    Map<String, Object> data =
        health.toggle(
            superAdmin, "PAUSE", "Runaway payout automation detected. Pausing all rules.");
    assertThat(data.get("kill_switch_status")).isEqualTo("PAUSED");
    assertThat(data.get("action")).isEqualTo("PAUSE");
    assertThat(data.get("executed_by")).isEqualTo(ADMIN);
    assertThat(data.get("estimated_effect_within_seconds")).isEqualTo(60);
    verify(killSwitch)
        .setStatus(
            eq(KillSwitchStatus.PAUSED),
            eq(ADMIN),
            eq("Runaway payout automation detected. Pausing all rules."));
  }

  @Test
  void ac002_opsForbiddenOnKillSwitch() {
    assertThatThrownBy(() -> health.toggle(ops, "PAUSE", "nope"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN");
              assertThat(((AppException) ex).httpStatus()).isEqualTo(403);
            });
    verify(killSwitch, never()).setStatus(any(), any(), any());
  }

  @Test
  void ac003_emptyReasonRequired() {
    assertThatThrownBy(() -> health.toggle(superAdmin, "PAUSE", "  "))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("REASON_REQUIRED"));
    assertThatThrownBy(() -> health.toggle(superAdmin, "PAUSE", null))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("REASON_REQUIRED"));
  }

  @Test
  void ac004_healthReflectsPauseAndResume() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);
    when(killSwitch.lastChange())
        .thenReturn(
            Optional.of(
                new KillSwitchChange(
                    KillSwitchAction.PAUSE, ADMIN, "admin@nammamedmate.in", NOW, "incident")));
    Map<String, Object> paused = health.dashboard(ops);
    assertThat(paused.get("kill_switch_status")).isEqualTo("PAUSED");
    @SuppressWarnings("unchecked")
    Map<String, Object> change = (Map<String, Object>) paused.get("last_kill_switch_change");
    assertThat(change.get("action")).isEqualTo("PAUSE");
    assertThat(change.get("changed_by")).isEqualTo("admin@nammamedmate.in");

    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    health.invalidateDashboardCache();
    assertThat(health.dashboard(superAdmin).get("kill_switch_status")).isEqualTo("ACTIVE");
  }

  @Test
  void ac005_circuitBreakersShowOpenAndResetAt() {
    Instant opened = Instant.parse("2026-07-24T09:30:00Z");
    Instant reset = Instant.parse("2026-07-24T10:00:00Z");
    when(circuits.list())
        .thenReturn(
            List.of(
                new CircuitBreakerState(
                    "auto_assign_rider", 50, CircuitStatus.CLOSED, 24, null, null, NOW),
                new CircuitBreakerState(
                    "apply_wallet_credit", 50, CircuitStatus.OPEN, 52, opened, reset, NOW)));
    Map<String, Object> data = health.circuitBreakers(ops);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("circuit_breakers");
    assertThat(rows.getFirst().get("circuit_status")).isEqualTo("CLOSED");
    assertThat(rows.get(1).get("circuit_status")).isEqualTo("OPEN");
    assertThat(rows.get(1).get("fires_last_hour")).isEqualTo(52);
    assertThat(rows.get(1).get("reset_at")).isEqualTo(reset.toString());
    assertThat(Duration.between(opened, reset).toMinutes()).isEqualTo(30);
  }

  @Test
  void ac007_perRuleSuccessRate() {
    when(activity.perRuleHealth(any()))
        .thenReturn(
            List.of(
                new RuleHealthMetrics(
                    RULE,
                    "Auto-assign unassigned orders",
                    "ACTIVE",
                    48,
                    47,
                    1,
                    "Dispatch API timeout after 5s",
                    Instant.parse("2026-07-24T06:42:00Z"),
                    387,
                    Instant.parse("2026-07-24T09:07:00Z")),
                new RuleHealthMetrics(
                    UUID.fromString("22222222-2222-4222-8222-222222222222"),
                    "Auto-release due payouts",
                    "ACTIVE",
                    12,
                    12,
                    0,
                    null,
                    null,
                    842,
                    Instant.parse("2026-07-24T07:00:00Z")),
                new RuleHealthMetrics(
                    UUID.fromString("33333333-3333-4333-8333-333333333333"),
                    "Quiet",
                    "INACTIVE",
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    null)));
    Map<String, Object> data = health.perRule(ops);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rules");
    assertThat(rows.getFirst().get("success_rate_pct")).isEqualTo(97.9);
    assertThat(rows.getFirst().get("exception_count_24h")).isEqualTo(1L);
    assertThat(rows.get(1).get("success_rate_pct")).isEqualTo(100.0);
    assertThat(rows.get(1).get("last_error")).isNull();
  }

  @Test
  void ac008_pauseAndResumeLoggedViaPort() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    health.toggle(superAdmin, "PAUSE", "investigating");
    verify(killSwitch).setStatus(KillSwitchStatus.PAUSED, ADMIN, "investigating");
    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);
    health.toggle(superAdmin, "RESUME", "Incident resolved. Resuming automation.");
    verify(killSwitch)
        .setStatus(KillSwitchStatus.ACTIVE, ADMIN, "Incident resolved. Resuming automation.");
  }

  @Test
  void ac009_pendingApprovalsUnaffectedAndDeferredUntilResume() {
    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);
    when(approvals.findById(APPR)).thenReturn(Optional.of(pending()));
    when(approvals.markResolved(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    Map<String, Object> approved = queue.approve(ops, APPR, "ok while paused");
    assertThat(approved.get("status")).isEqualTo("APPROVED");
    assertThat(approved.get("action_executed")).isEqualTo(false);
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
    verify(deferred).enqueue(eq(APPR), eq("release_payout"), anyMap(), anyMap());

    UUID defId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    when(deferred.listAll())
        .thenReturn(
            List.of(
                new DeferredExecution(
                    defId, APPR, "release_payout", Map.of("amount_paise", 1), Map.of(), NOW)));
    when(actions.execute(eq("release_payout"), anyMap(), anyMap())).thenReturn(ACT);
    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);
    health.toggle(superAdmin, "RESUME", "all clear");
    verify(actions).execute(eq("release_payout"), anyMap(), anyMap());
    verify(deferred).delete(defId);
  }

  @Test
  void dashboardCachedSixtySecondsAndAlreadyInState() {
    Map<String, Object> first = health.dashboard(ops);
    assertThat(first.get("actions_today")).isEqualTo(284L);
    assertThat(first.get("pending_approvals")).isEqualTo(3L);
    assertThat(first.get("data_as_of")).isEqualTo(NOW.toString());
    verify(activity).stats(NOW);
    health.dashboard(ops);
    verify(activity).stats(NOW);

    java.util.concurrent.atomic.AtomicReference<Instant> tick =
        new java.util.concurrent.atomic.AtomicReference<>(NOW);
    Clock moving =
        new Clock() {
          @Override
          public Instant instant() {
            return tick.get();
          }

          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }
        };
    AutomationHealthService movingHealth =
        new AutomationHealthService(killSwitch, activity, rules, approvals, circuits, moving);
    movingHealth.dashboard(ops);
    tick.set(NOW.plusSeconds(61));
    movingHealth.dashboard(ops);
    verify(activity, org.mockito.Mockito.times(3)).stats(any());

    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    assertThatThrownBy(() -> health.toggle(superAdmin, "RESUME", "already on"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("ALREADY_IN_STATE"));
    assertThatThrownBy(() -> health.toggle(superAdmin, "NOPE", "x"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(() -> health.dashboard(null))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    assertThatThrownBy(() -> health.toggle(null, "PAUSE", "x"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    assertThatThrownBy(() -> health.dashboard(principal(AuthRole.ADMIN_FINANCE)))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void constructorsAndNullApprovalsPending() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    AutomationHealthService noQueue =
        new AutomationHealthService(killSwitch, activity, rules, null, circuits, clock);
    assertThat(noQueue.dashboard(ops).get("pending_approvals")).isEqualTo(3L);
    noQueue.invalidateDashboardCache();
    when(killSwitch.status()).thenReturn(KillSwitchStatus.PAUSED);
    noQueue.toggle(superAdmin, "RESUME", "no queue");
    verify(killSwitch).setStatus(KillSwitchStatus.ACTIVE, ADMIN, "no queue");
  }

  private AutomationApproval pending() {
    return new AutomationApproval(
        APPR,
        RULE,
        "rule",
        null,
        "payout_due",
        "release_payout",
        Map.of("amount_paise", 100L),
        "PHARMACY",
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "Store",
        100L,
        ApprovalCategory.FINANCE,
        ApprovalUrgency.NORMAL,
        "cap",
        Map.of(),
        List.of(),
        "impact",
        null,
        ApprovalStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        NOW.minusSeconds(60),
        NOW.plus(Duration.ofHours(3)),
        null);
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(ADMIN, role, null, TokenScope.FULL, "j");
  }
}
