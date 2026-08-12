package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ApprovalNotifyPort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.ApprovalQueueStats;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.Chips;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
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
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalQueueServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:45:00Z");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID RULE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID APPR = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACT = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Mock ApprovalStorePort store;
  @Mock ApprovalNotifyPort notify;
  @Mock ActionExecutorPort actions;
  @Mock ActivityLogPort activityLog;

  private ApprovalQueueService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal ops;
  private MedmatePrincipal finance;

  @BeforeEach
  void setUp() {
    service =
        new ApprovalQueueService(
            store, notify, actions, activityLog, Clock.fixed(NOW, ZoneOffset.UTC));
    superAdmin = principal(AuthRole.ADMIN_SUPER);
    ops = principal(AuthRole.ADMIN_OPERATIONS);
    finance = principal(AuthRole.ADMIN_FINANCE);
  }

  @Test
  void ac001_listChipsMatchPendingCount() {
    when(store.count(ApprovalStatus.PENDING, null)).thenReturn(3L);
    when(store.list(eq(ApprovalStatus.PENDING), isNull(), eq(0), eq(20)))
        .thenReturn(List.of(pending(ApprovalCategory.FINANCE), pending(ApprovalCategory.ADMIN)));
    when(store.chips(NOW)).thenReturn(new Chips(3, 1, 8, 1));
    ApprovalQueueService.PagedResult out = service.list(superAdmin, null, null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) out.data().get("chips");
    assertThat(chips.get("pending_count")).isEqualTo(3L);
    assertThat(chips.get("urgent_count")).isEqualTo(1L);
    assertThat(out.meta().total()).isEqualTo(3L);
  }

  @Test
  void ac002_approveExecutesWithHumanActor() {
    when(store.findById(APPR)).thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)));
    when(actions.execute(eq("release_payout"), anyMap(), anyMap())).thenReturn(ACT);
    when(store.markResolved(
            eq(APPR),
            eq(ApprovalStatus.PENDING),
            eq(ApprovalStatus.APPROVED),
            eq(ADMIN),
            any(),
            any(),
            eq(ACT),
            eq(NOW)))
        .thenReturn(1);
    Map<String, Object> data = service.approve(ops, APPR, "Verified.");
    assertThat(data.get("status")).isEqualTo("APPROVED");
    assertThat(data.get("action_executed")).isEqualTo(true);
    assertThat(data.get("activity_log_id")).isEqualTo(ACT);
    assertThat(data.get("approved_by")).isEqualTo(ADMIN);
    org.mockito.ArgumentCaptor<Map<String, Object>> cap =
        org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(actions).execute(eq("release_payout"), anyMap(), cap.capture());
    assertThat(cap.getValue().get("actor")).isEqualTo("HUMAN");
    assertThat(cap.getValue().get("override_by")).isEqualTo(ADMIN.toString());
  }

  @Test
  void ac003_approveExpiredReturns410() {
    AutomationApproval expired =
        approval(ApprovalStatus.EXPIRED, ApprovalCategory.FINANCE, NOW.minusSeconds(10));
    when(store.findById(APPR)).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> service.approve(superAdmin, APPR, "x"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_EXPIRED");
              assertThat(((AppException) ex).httpStatus()).isEqualTo(410);
            });
  }

  @Test
  void ac004_rejectEmptyReason400() {
    assertThatThrownBy(() -> service.reject(superAdmin, APPR, "  "))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("REASON_REQUIRED"));
    assertThatThrownBy(() -> service.reject(superAdmin, APPR, null))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("REASON_REQUIRED"));
    when(store.findById(APPR)).thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)));
    when(activityLog.append(anyString(), eq("REJECTED"), anyString(), anyMap())).thenReturn(ACT);
    when(store.markResolved(
            any(), any(), eq(ApprovalStatus.REJECTED), any(), any(), any(), any(), any()))
        .thenReturn(1);
    assertThat(service.reject(ops, APPR, "hold").get("alternative_action_fired")).isEqualTo(false);
  }

  @Test
  void ac005_financeApprovesFinanceCategory() {
    when(store.findById(APPR)).thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)));
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(ACT);
    when(store.markResolved(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(service.approve(finance, APPR, "ok").get("status")).isEqualTo("APPROVED");
  }

  @Test
  void ac006_financeCannotApproveAdminCategory() {
    when(store.findById(APPR)).thenReturn(Optional.of(pending(ApprovalCategory.ADMIN)));
    assertThatThrownBy(() -> service.approve(finance, APPR, "no"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN");
              assertThat(((AppException) ex).httpStatus()).isEqualTo(403);
            });
    when(store.findById(APPR))
        .thenReturn(
            Optional.of(
                approval(ApprovalStatus.APPROVED, ApprovalCategory.FINANCE, NOW.plusSeconds(60))));
    assertThatThrownBy(() -> service.approve(superAdmin, APPR, "x"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_ALREADY_RESOLVED"));
    when(store.findById(APPR))
        .thenReturn(Optional.of(approval(ApprovalStatus.EXPIRED, ApprovalCategory.FINANCE, NOW)));
    assertThatThrownBy(() -> service.reject(superAdmin, APPR, "late"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_EXPIRED"));
  }

  @Test
  void ac007_expiryFiresAlternative() {
    AutomationApproval due =
        new AutomationApproval(
            APPR,
            RULE,
            "r",
            ACT,
            "payout_cycle_reached",
            "release_payout",
            Map.of("amount_paise", 4800000),
            "PHARMACY",
            ENTITY,
            "Apollo",
            4_800_000L,
            ApprovalCategory.FINANCE,
            ApprovalUrgency.URGENT,
            "cap",
            Map.of("payout_amount_paise", 4800000),
            List.of(),
            "impact",
            "open_csm_task",
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            ACT,
            NOW.minus(Duration.ofHours(5)),
            NOW.minusSeconds(1),
            null);
    when(store.listExpired(NOW, 100)).thenReturn(List.of(due));
    when(store.markResolved(
            eq(APPR),
            eq(ApprovalStatus.PENDING),
            eq(ApprovalStatus.EXPIRED),
            isNull(),
            isNull(),
            eq("expired"),
            eq(ACT),
            eq(NOW)))
        .thenReturn(1);
    when(actions.execute(eq("open_csm_task"), anyMap(), anyMap())).thenReturn(UUID.randomUUID());
    assertThat(service.expireDue(100)).isEqualTo(1);
    verify(actions).execute(eq("open_csm_task"), anyMap(), anyMap());
    verify(notify).approvalExpired(APPR, "release_payout");
  }

  @Test
  void ac008_enqueueNotifiesApproversHigh() {
    when(store.findPending(RULE, ENTITY, "release_payout")).thenReturn(Optional.empty());
    when(activityLog.append(anyString(), eq("PENDING_APPROVAL"), anyString(), anyMap()))
        .thenReturn(ACT);
    UUID id =
        service.enqueue(
            new ApprovalQueueService.EnqueueCommand(
                RULE,
                "Auto-release",
                ACT,
                "payout_cycle_reached",
                "release_payout",
                Map.of("amount_paise", 4800000),
                "PHARMACY",
                ENTITY,
                "Apollo",
                4_800_000L,
                ApprovalCategory.FINANCE,
                ApprovalUrgency.URGENT,
                "cap",
                Map.of("x", 1),
                List.of(),
                "impact",
                null,
                null));
    assertThat(id).isNotNull();
    verify(store).insert(any());
    verify(notify).approvalRequested(eq(id), eq("release_payout"), eq("URGENT"), anyString());
  }

  @Test
  void ac009_statsAvgResponseLast7Days() {
    when(store.stats(NOW))
        .thenReturn(
            new ApprovalQueueStats(
                18.4, 88.9, 11.1, 4.2, List.of(Map.of("category", "FINANCE", "count", 2L))));
    Map<String, Object> data = service.stats(superAdmin);
    assertThat(data.get("avg_response_time_minutes")).isEqualTo(18.4);
    assertThat(data.get("approval_rate_pct")).isEqualTo(88.9);
    assertThat(data.get("period_days")).isEqualTo(7);
    assertThatThrownBy(() -> service.stats(finance))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void enqueueDedupRenotifies() {
    when(store.findPending(RULE, ENTITY, "release_payout"))
        .thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)));
    UUID id =
        service.enqueue(
            new ApprovalQueueService.EnqueueCommand(
                RULE,
                "r",
                null,
                "t",
                "release_payout",
                Map.of(),
                "PHARMACY",
                ENTITY,
                "n",
                1L,
                ApprovalCategory.FINANCE,
                ApprovalUrgency.NORMAL,
                "w",
                Map.of(),
                List.of(),
                "i",
                null,
                null));
    assertThat(id).isEqualTo(APPR);
    verify(store, never()).insert(any());
    verify(notify).approvalRequested(eq(APPR), anyString(), anyString(), anyString());
  }

  @Test
  void enqueueDuplicateKeyFallsBack() {
    when(store.findPending(RULE, ENTITY, "release_payout"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)));
    when(activityLog.append(anyString(), anyString(), anyString(), anyMap())).thenReturn(ACT);
    doThrow(new DuplicateKeyException("dup")).when(store).insert(any());
    UUID id =
        service.enqueue(
            new ApprovalQueueService.EnqueueCommand(
                RULE,
                "r",
                null,
                "t",
                "release_payout",
                null,
                "PHARMACY",
                ENTITY,
                "n",
                null,
                null,
                null,
                "w",
                null,
                null,
                "i",
                null,
                Duration.ofHours(1)));
    assertThat(id).isEqualTo(APPR);
  }

  @Test
  void enqueueDuplicateKeyRethrowsWhenGone() {
    when(store.findPending(RULE, ENTITY, "release_payout")).thenReturn(Optional.empty());
    when(activityLog.append(anyString(), anyString(), anyString(), anyMap())).thenReturn(ACT);
    doThrow(new DuplicateKeyException("dup")).when(store).insert(any());
    assertThatThrownBy(
            () ->
                service.enqueue(
                    new ApprovalQueueService.EnqueueCommand(
                        RULE,
                        "r",
                        null,
                        "t",
                        "release_payout",
                        Map.of(),
                        "PHARMACY",
                        ENTITY,
                        "n",
                        1L,
                        ApprovalCategory.FINANCE,
                        ApprovalUrgency.NORMAL,
                        "w",
                        Map.of(),
                        List.of(),
                        "i",
                        null,
                        null)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void rejectFiresAlternativeAndAlreadyResolved() {
    AutomationApproval pendingAlt =
        new AutomationApproval(
            APPR,
            RULE,
            "r",
            ACT,
            "t",
            "release_payout",
            Map.of(),
            "PHARMACY",
            ENTITY,
            "n",
            1L,
            ApprovalCategory.FINANCE,
            ApprovalUrgency.NORMAL,
            "w",
            Map.of("k", "v"),
            List.of(),
            "i",
            "open_csm_task",
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            ACT,
            NOW,
            NOW.plus(Duration.ofHours(4)),
            null);
    when(store.findById(APPR)).thenReturn(Optional.of(pendingAlt));
    when(actions.execute(eq("open_csm_task"), anyMap(), anyMap())).thenReturn(UUID.randomUUID());
    when(activityLog.append(anyString(), eq("REJECTED"), anyString(), anyMap())).thenReturn(ACT);
    when(store.markResolved(
            any(), any(), eq(ApprovalStatus.REJECTED), any(), any(), any(), any(), any()))
        .thenReturn(1);
    Map<String, Object> data = service.reject(superAdmin, APPR, "hold");
    assertThat(data.get("alternative_action_fired")).isEqualTo(true);
    assertThat(data.get("status")).isEqualTo("REJECTED");

    when(store.findById(APPR))
        .thenReturn(Optional.of(approval(ApprovalStatus.APPROVED, ApprovalCategory.FINANCE, NOW)));
    assertThatThrownBy(() -> service.reject(superAdmin, APPR, "again"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_ALREADY_RESOLVED"));
  }

  @Test
  void approveLazyExpiryAndRace() {
    AutomationApproval stale =
        approval(ApprovalStatus.PENDING, ApprovalCategory.FINANCE, NOW.minusSeconds(1));
    when(store.findById(APPR)).thenReturn(Optional.of(stale));
    when(store.markResolved(
            eq(APPR),
            eq(ApprovalStatus.PENDING),
            eq(ApprovalStatus.EXPIRED),
            isNull(),
            isNull(),
            eq("expired"),
            any(),
            eq(NOW)))
        .thenReturn(1);
    assertThatThrownBy(() -> service.approve(superAdmin, APPR, "x"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_EXPIRED"));

    when(store.findById(APPR))
        .thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)))
        .thenReturn(Optional.of(approval(ApprovalStatus.APPROVED, ApprovalCategory.FINANCE, NOW)));
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(ACT);
    when(store.markResolved(
            eq(APPR),
            eq(ApprovalStatus.PENDING),
            eq(ApprovalStatus.APPROVED),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(0);
    assertThatThrownBy(() -> service.approve(superAdmin, APPR, "x"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_ALREADY_RESOLVED"));
  }

  @Test
  void getListAuthAndValidation() {
    when(store.findById(APPR)).thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)));
    when(store.chips(NOW)).thenReturn(new Chips(0, 0, 0, 0));
    when(store.count(any(), any())).thenReturn(0L);
    when(store.list(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    assertThat(service.get(ops, APPR).get("approval_id")).isEqualTo(APPR);
    assertThatThrownBy(() -> service.get(superAdmin, UUID.randomUUID()))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_NOT_FOUND"));
    assertThatThrownBy(() -> service.list(null, null, null, 0, 0))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    assertThatThrownBy(() -> service.list(principal(AuthRole.CUSTOMER), "PENDING", null, 1, 20))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.list(superAdmin, "NOPE", null, 1, 20))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(() -> service.list(superAdmin, "PENDING", "NOPE", 1, 20))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    service.list(superAdmin, "APPROVED", "URGENT", 2, 200);
    verify(store).list(eq(ApprovalStatus.APPROVED), eq(ApprovalUrgency.URGENT), eq(100), eq(100));
    assertThat(service.pendingCount()).isEqualTo(0L);
    when(store.countPending()).thenReturn(4L);
    assertThat(service.pendingCount()).isEqualTo(4L);
    assertThatThrownBy(() -> service.stats(null))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
  }

  @Test
  void expireSkipAndAltFailureAndTtlCtor() {
    when(store.listExpired(NOW, 10)).thenReturn(List.of(pending(ApprovalCategory.ADMIN)));
    when(store.markResolved(
            any(), any(), eq(ApprovalStatus.EXPIRED), any(), any(), any(), any(), any()))
        .thenReturn(0);
    assertThat(service.expireDue(10)).isZero();

    AutomationApproval due =
        new AutomationApproval(
            APPR,
            RULE,
            "r",
            null,
            "t",
            "suspend_entity",
            Map.of(),
            "RIDER",
            ENTITY,
            "n",
            null,
            ApprovalCategory.ADMIN,
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
            NOW.minusSeconds(10),
            NOW.minusSeconds(1),
            null);
    when(store.listExpired(NOW, 10)).thenReturn(List.of(due));
    when(store.markResolved(
            any(), any(), eq(ApprovalStatus.EXPIRED), any(), any(), any(), any(), any()))
        .thenReturn(1);
    doThrow(new RuntimeException("x"))
        .when(actions)
        .execute(eq("open_csm_task"), anyMap(), anyMap());
    assertThat(service.expireDue(10)).isEqualTo(1);

    new ApprovalQueueService(
        store, notify, actions, activityLog, Clock.fixed(NOW, ZoneOffset.UTC), null);
    new ApprovalQueueService(
        store,
        notify,
        actions,
        activityLog,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(-1));
    ApprovalQueueService zeroTtl =
        new ApprovalQueueService(
            store, notify, actions, activityLog, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO);
    when(store.findPending(any(), any(), any())).thenReturn(Optional.empty());
    when(activityLog.append(anyString(), anyString(), any(), anyMap())).thenReturn(ACT);
    zeroTtl.enqueue(
        new ApprovalQueueService.EnqueueCommand(
            RULE,
            null,
            null,
            null,
            "x",
            Map.of(),
            null,
            ENTITY,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            null,
            null,
            Duration.ofMinutes(-1)));
    verify(store).insert(any());

    when(store.findById(APPR))
        .thenReturn(Optional.of(pending(ApprovalCategory.FINANCE)))
        .thenReturn(Optional.of(approval(ApprovalStatus.EXPIRED, ApprovalCategory.FINANCE, NOW)));
    when(activityLog.append(anyString(), eq("REJECTED"), anyString(), anyMap())).thenReturn(ACT);
    when(store.markResolved(
            any(), any(), eq(ApprovalStatus.REJECTED), any(), any(), any(), any(), any()))
        .thenReturn(0);
    assertThatThrownBy(() -> service.reject(superAdmin, APPR, "gone"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVAL_EXPIRED"));
  }

  private AutomationApproval pending(ApprovalCategory cat) {
    return approval(ApprovalStatus.PENDING, cat, NOW.plus(Duration.ofHours(4)));
  }

  private AutomationApproval approval(
      ApprovalStatus status, ApprovalCategory cat, Instant expires) {
    return new AutomationApproval(
        APPR,
        RULE,
        "Auto-release due payouts",
        ACT,
        "payout_cycle_reached",
        cat == ApprovalCategory.ADMIN ? "suspend_entity" : "release_payout",
        Map.of("amount_paise", 4800000, "mode", "IMPS"),
        "PHARMACY",
        ENTITY,
        "Apollo Pharmacy - Indiranagar",
        4_800_000L,
        cat,
        ApprovalUrgency.URGENT,
        "cap",
        Map.of("payout_amount_paise", 4800000),
        List.of(Map.of("field", "payout.amount", "result", false)),
        "Release Rs 48000.",
        null,
        status,
        null,
        null,
        null,
        null,
        ACT,
        NOW.minus(Duration.ofHours(1)),
        expires,
        status == ApprovalStatus.PENDING ? null : NOW);
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(ADMIN, role, null, TokenScope.FULL, "j");
  }
}
