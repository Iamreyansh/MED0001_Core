package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort.ActivityQuery;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.domain.ActivityLogEntry;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.ActivityStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityLogServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:40:00Z");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID RULE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACT = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID RB = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Mock ActivityLogPort store;
  @Mock RuleStorePort rules;
  @Mock ActionExecutorPort actions;

  private ActivityLogService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal ops;
  private MedmatePrincipal finance;

  @BeforeEach
  void setUp() {
    service = new ActivityLogService(store, rules, actions, Clock.fixed(NOW, ZoneOffset.UTC));
    superAdmin = principal(AuthRole.ADMIN_SUPER);
    ops = principal(AuthRole.ADMIN_OPERATIONS);
    finance = principal(AuthRole.ADMIN_FINANCE);
  }

  @Test
  void ac001_listFiltersSimulatedAndTagsRuleName() {
    when(store.count(any())).thenReturn(1L);
    when(store.list(any(), anyInt(), anyInt()))
        .thenReturn(List.of(entry("auto_assign_rider", ActivityStatus.SIMULATED, false)));
    ActivityLogService.PagedResult out =
        service.list(superAdmin, "SIMULATED", RULE, "DISPATCH", "ORDER", null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) out.data().get("activity");
    assertThat(items).hasSize(1);
    assertThat(items.getFirst().get("status")).isEqualTo("SIMULATED");
    assertThat(items.getFirst().get("rule_name").toString()).contains("[SIMULATED]");
    ArgumentCaptor<ActivityQuery> cap = ArgumentCaptor.forClass(ActivityQuery.class);
    verify(store).list(cap.capture(), eq(0), eq(20));
    assertThat(cap.getValue().status()).isEqualTo("SIMULATED");
    assertThat(cap.getValue().ruleId()).isEqualTo(RULE);
  }

  @Test
  void ac002_detailIncludesBeforeAfter() {
    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("auto_assign_rider", ActivityStatus.EXECUTED, false)));
    Map<String, Object> data = service.get(ops, ACT);
    assertThat(data.get("before_state"))
        .isEqualTo(Map.of("order_status", "PLACED", "rider_id", ""));
    assertThat(data.get("after_state"))
        .isEqualTo(Map.of("order_status", "ACCEPTED", "rider_id", "uuid-rider-8"));
    assertThat(data.get("status")).isEqualTo("EXECUTED");
  }

  @Test
  void ac003_rollbackSuspendReactivatesAndLogs() {
    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("suspend_entity", ActivityStatus.EXECUTED, false)));
    when(store.existsRollbackFor(ACT)).thenReturn(false);
    when(store.append(eq("ROLLBACK"), eq("ROLLED_BACK"), anyString(), anyMap())).thenReturn(RB);
    when(actions.execute(eq("reactivate_entity"), anyMap(), anyMap()))
        .thenReturn(UUID.randomUUID());

    Map<String, Object> data = service.rollback(superAdmin, ACT, "Rule misconfigured");
    assertThat(data.get("rollback_action_id")).isEqualTo(RB);
    assertThat(data.get("original_action_id")).isEqualTo(ACT);
    assertThat(data.get("action_type")).isEqualTo("ROLLBACK");
    assertThat(data.get("rolled_back_action")).isEqualTo("suspend_entity");
    assertThat(data.get("result").toString()).contains("reactivated");
    verify(actions).execute(eq("reactivate_entity"), anyMap(), anyMap());
  }

  @Test
  void ac004_releasePayoutNotRollbackable() {
    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("release_payout", ActivityStatus.EXECUTED, false)));
    when(store.existsRollbackFor(ACT)).thenReturn(false);
    assertThatThrownBy(() -> service.rollback(ops, ACT, "nope"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("NOT_ROLLBACKABLE"));
    verify(actions, never()).execute(anyString(), anyMap(), anyMap());
  }

  @Test
  void ac005_alreadyRolledBack() {
    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("suspend_entity", ActivityStatus.EXECUTED, true)));
    when(store.existsRollbackFor(ACT)).thenReturn(true);
    assertThatThrownBy(() -> service.rollback(superAdmin, ACT, "again"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("ALREADY_ROLLED_BACK"));
  }

  @Test
  void ac006_007_statsPendingAndManualSaved() {
    when(store.stats(NOW)).thenReturn(new ActivityStats(284, 1842, 1842, 2, 3, NOW));
    when(rules.countByStatus(RuleStatus.ACTIVE)).thenReturn(8L);
    when(rules.countByStatus(RuleStatus.SIMULATING)).thenReturn(1L);
    when(rules.countByStatus(RuleStatus.INACTIVE)).thenReturn(3L);
    Map<String, Object> data = service.stats(superAdmin);
    assertThat(data.get("pending_approvals_count")).isEqualTo(3L);
    assertThat(data.get("manual_actions_saved_estimate")).isEqualTo(1842L);
    assertThat(data.get("exceptions_raised_today")).isEqualTo(2L);
    assertThat(data.get("rules_active")).isEqualTo(8L);
    assertThat(data.get("last_action_at")).isEqualTo(NOW.toString());
  }

  @Test
  void ac008_rateLimitedVisibleInFeed() {
    when(store.count(any())).thenReturn(1L);
    when(store.list(any(), anyInt(), anyInt()))
        .thenReturn(List.of(entry("rate_limit", ActivityStatus.RATE_LIMITED, false)));
    ActivityLogService.PagedResult out =
        service.list(ops, "RATE_LIMITED", null, null, null, null, null, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) out.data().get("activity");
    assertThat(items.getFirst().get("status")).isEqualTo("RATE_LIMITED");
    assertThat(out.meta().limit()).isEqualTo(20);
  }

  @Test
  void ac009_dateRangeFilterUnder500ms() {
    when(store.count(any())).thenReturn(0L);
    when(store.list(any(), anyInt(), anyInt())).thenReturn(List.of());
    Instant start = Instant.now();
    service.list(
        superAdmin, null, null, null, null, "2026-07-01T00:00:00Z", "2026-07-24T23:59:59Z", 1, 20);
    assertThat(Duration.between(start, Instant.now()).toMillis()).isLessThan(500);
    ArgumentCaptor<ActivityQuery> cap = ArgumentCaptor.forClass(ActivityQuery.class);
    verify(store).list(cap.capture(), eq(0), eq(20));
    assertThat(cap.getValue().dateFrom()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    assertThat(cap.getValue().dateTo()).isEqualTo(Instant.parse("2026-07-24T23:59:59Z"));
  }

  @Test
  void financeSeesOnlyFinancialAndCannotRollback() {
    when(store.count(any())).thenReturn(0L);
    when(store.list(any(), anyInt(), anyInt())).thenReturn(List.of());
    service.list(finance, null, null, null, null, null, null, 1, 20);
    ArgumentCaptor<ActivityQuery> cap = ArgumentCaptor.forClass(ActivityQuery.class);
    verify(store).list(cap.capture(), anyInt(), anyInt());
    assertThat(cap.getValue().actionTypesOnly()).contains("release_payout");

    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("auto_assign_rider", ActivityStatus.EXECUTED, false)));
    assertThatThrownBy(() -> service.get(finance, ACT))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("ACTION_NOT_FOUND"));

    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("apply_wallet_credit", ActivityStatus.EXECUTED, false)));
    assertThat(service.get(finance, ACT).get("action_type")).isEqualTo("apply_wallet_credit");

    ActivityLogEntry rb =
        new ActivityLogEntry(
            ACT,
            RULE,
            "r",
            null,
            null,
            null,
            Map.of(),
            null,
            "CUSTOMER",
            ENTITY,
            "c",
            "ROLLBACK",
            Map.of("rolled_back_action", "apply_wallet_credit"),
            List.of(),
            null,
            null,
            ActivityStatus.ROLLED_BACK,
            "HUMAN",
            ADMIN,
            NOW,
            NOW,
            1,
            ACT,
            "x",
            NOW,
            false,
            null);
    when(store.findById(ACT)).thenReturn(Optional.of(rb));
    assertThat(service.get(finance, ACT).get("action_type")).isEqualTo("ROLLBACK");

    ActivityLogEntry rbBad =
        new ActivityLogEntry(
            ACT,
            RULE,
            "r",
            null,
            null,
            null,
            Map.of(),
            null,
            "PHARMACY",
            ENTITY,
            "p",
            "ROLLBACK",
            Map.of("rolled_back_action", "suspend_entity"),
            List.of(),
            null,
            null,
            ActivityStatus.ROLLED_BACK,
            "HUMAN",
            ADMIN,
            NOW,
            NOW,
            1,
            ACT,
            "x",
            NOW,
            false,
            null);
    when(store.findById(ACT)).thenReturn(Optional.of(rbBad));
    assertThatThrownBy(() -> service.get(finance, ACT)).isInstanceOf(AppException.class);

    ActivityLogEntry rbEmpty =
        new ActivityLogEntry(
            ACT,
            RULE,
            "r",
            null,
            null,
            null,
            Map.of(),
            null,
            "PHARMACY",
            ENTITY,
            "p",
            "ROLLBACK",
            Map.of(),
            List.of(),
            null,
            null,
            ActivityStatus.ROLLED_BACK,
            "HUMAN",
            ADMIN,
            NOW,
            NOW,
            1,
            ACT,
            "x",
            NOW,
            false,
            null);
    when(store.findById(ACT)).thenReturn(Optional.of(rbEmpty));
    assertThatThrownBy(() -> service.get(finance, ACT)).isInstanceOf(AppException.class);

    assertThatThrownBy(() -> service.stats(finance)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.rollback(finance, ACT, "no"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void validationAndAuthBranches() {
    assertThatThrownBy(() -> service.list(superAdmin, "NOPE", null, null, null, null, null, 0, 0))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.list(superAdmin, null, null, null, null, "bad", null, 1, 200))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () -> service.list(superAdmin, " ", null, " ", " ", " ", "not-instant", -1, 200))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, 1, 20))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.list(
                    principal(AuthRole.CUSTOMER), null, null, null, null, null, null, 1, 20))
        .isInstanceOf(AppException.class);
    when(store.findById(ACT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(ops, ACT))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("ACTION_NOT_FOUND"));
    assertThatThrownBy(() -> service.rollback(superAdmin, ACT, " "))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.rollback(superAdmin, ACT, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.rollback(null, ACT, "r")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.stats(null)).isInstanceOf(AppException.class);

    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("apply_wallet_credit", ActivityStatus.EXECUTED, false)));
    when(store.existsRollbackFor(ACT)).thenReturn(false);
    when(store.append(anyString(), anyString(), anyString(), anyMap())).thenReturn(RB);
    Map<String, Object> wallet = service.rollback(ops, ACT, "debit it");
    assertThat(wallet.get("result").toString()).contains("debited");
    verify(actions, never()).execute(eq("reactivate_entity"), anyMap(), anyMap());

    when(store.findById(ACT))
        .thenReturn(Optional.of(entry("suspend_entity", ActivityStatus.SIMULATED, false)));
    assertThatThrownBy(() -> service.rollback(ops, ACT, "no"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("NOT_ROLLBACKABLE"));

    when(store.stats(NOW)).thenReturn(new ActivityStats(0, 0, 0, 0, 0, null));
    when(rules.countByStatus(any())).thenReturn(0L);
    assertThat(service.stats(ops).get("last_action_at")).isNull();

    when(store.count(any())).thenReturn(0L);
    when(store.list(any(), anyInt(), anyInt())).thenReturn(List.of());
    service.list(superAdmin, null, null, null, null, null, null, 1, 200);
    service.list(superAdmin, null, null, "  ", "  ", null, null, 0, 0);

    ActivityLogEntry nullIds =
        new ActivityLogEntry(
            ACT,
            null,
            null,
            null,
            null,
            "t",
            Map.of(),
            null,
            "PHARMACY",
            null,
            null,
            "suspend_entity",
            Map.of("reason", "x"),
            List.of(),
            null,
            null,
            ActivityStatus.EXECUTED,
            "AUTOMATION",
            null,
            NOW,
            null,
            null,
            null,
            null,
            NOW,
            false,
            null);
    when(store.findById(ACT)).thenReturn(Optional.of(nullIds));
    when(store.existsRollbackFor(ACT)).thenReturn(false);
    when(store.append(anyString(), anyString(), anyString(), anyMap())).thenReturn(RB);
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(UUID.randomUUID());
    assertThat(service.rollback(superAdmin, ACT, "fix").get("entity_id")).isNull();
    assertThat(service.get(ops, ACT).get("executed_at")).isNull();
  }

  @Test
  void simulatedRuleNameAlreadyTagged() {
    ActivityLogEntry e =
        new ActivityLogEntry(
            ACT,
            RULE,
            "Seed [SIMULATED]",
            null,
            null,
            "t",
            Map.of(),
            null,
            "ORDER",
            ENTITY,
            null,
            "send_notification",
            Map.of(),
            List.of(),
            null,
            null,
            ActivityStatus.SIMULATED,
            "AUTOMATION",
            null,
            NOW,
            null,
            null,
            null,
            null,
            NOW,
            false,
            null);
    when(store.count(any())).thenReturn(1L);
    ActivityLogEntry unnamed =
        new ActivityLogEntry(
            ACT,
            RULE,
            null,
            null,
            null,
            "t",
            Map.of(),
            null,
            "ORDER",
            ENTITY,
            "n",
            "x",
            Map.of(),
            List.of(),
            null,
            null,
            ActivityStatus.EXECUTED,
            "AUTOMATION",
            null,
            NOW,
            NOW,
            1,
            null,
            null,
            NOW,
            false,
            null);
    when(store.list(any(), anyInt(), anyInt())).thenReturn(List.of(e, unnamed));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items =
        (List<Map<String, Object>>)
            service
                .list(superAdmin, null, null, null, null, null, null, 2, 50)
                .data()
                .get("activity");
    assertThat(items.getFirst().get("rule_name")).isEqualTo("Seed [SIMULATED]");

    ActivityLogEntry blankName =
        new ActivityLogEntry(
            ACT,
            null,
            "",
            null,
            null,
            null,
            Map.of(),
            NOW,
            "ORDER",
            ENTITY,
            "n",
            "x",
            Map.of(),
            List.of(),
            null,
            null,
            ActivityStatus.SIMULATED,
            "AUTOMATION",
            null,
            NOW,
            NOW,
            1,
            null,
            null,
            NOW,
            false,
            RB);
    when(store.list(any(), anyInt(), anyInt())).thenReturn(List.of(blankName));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tagged =
        (List<Map<String, Object>>)
            service
                .list(superAdmin, null, null, null, null, null, null, 1, 20)
                .data()
                .get("activity");
    assertThat(tagged.getFirst().get("rule_name")).isEqualTo("[SIMULATED]");
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(ADMIN, role, null, TokenScope.FULL, "j");
  }

  private static ActivityLogEntry entry(String action, ActivityStatus status, boolean rolledBack) {
    return new ActivityLogEntry(
        ACT,
        RULE,
        "Auto-assign unassigned orders",
        null,
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        "order_unassigned",
        Map.of("minutes_unassigned", 7),
        Instant.parse("2026-07-24T08:07:00Z"),
        "ORDER",
        ENTITY,
        "ORD-8821 (Ravi Kumar)",
        action,
        Map.of("order_id", ENTITY.toString()),
        List.of(Map.of("field", "zone.coverage_status", "result", true)),
        Map.of("order_status", "PLACED", "rider_id", ""),
        Map.of("order_status", "ACCEPTED", "rider_id", "uuid-rider-8"),
        status,
        "AUTOMATION",
        null,
        Instant.parse("2026-07-24T08:07:00Z"),
        Instant.parse("2026-07-24T08:07:01Z"),
        420,
        null,
        null,
        NOW,
        rolledBack,
        rolledBack ? RB : null);
  }
}
