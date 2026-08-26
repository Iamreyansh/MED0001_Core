package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.out.messaging.StubNotificationDispatchClient;
import com.nammamedmate.pharmacy.adapter.out.payout.StubRazorpayXPayoutClient;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore;
import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore.CommissionHistoryRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.RazorpayXPayoutPort;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListFilter;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListResult;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.SettlementRow;
import com.nammamedmate.pharmacy.domain.SettlementCalculator;
import com.nammamedmate.pharmacy.domain.SettlementPeriod;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AC coverage for STORY-004-003 commission & payout management. */
class AdminPharmacyCommissionSettlementServiceTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID FINANCE = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SETTLEMENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String WEBHOOK_SECRET = "test-razorpayx-webhook-secret";

  private FakePharmacyStore pharmacies;
  private FakeCommissionHistory commissionHistory;
  private FakeSettlementStore settlements;
  private SeedableOrderMetrics orderMetrics;
  private FakeProfileStore profiles;
  private FakeAudit audit;
  private InMemoryOutboxStore outboxStore;
  private RateLimiter rateLimiter;
  private AdminPharmacyCommissionService commissionService;
  private AdminPharmacySettlementService settlementService;
  private CommissionApplyService commissionApplyService;
  private SettlementGenerationService generationService;

  @BeforeEach
  void setUp() {
    pharmacies = new FakePharmacyStore();
    commissionHistory = new FakeCommissionHistory();
    settlements = new FakeSettlementStore();
    orderMetrics = new SeedableOrderMetrics();
    profiles = new FakeProfileStore();
    audit = new FakeAudit();
    outboxStore = new InMemoryOutboxStore();
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);

    Clock clock = Clock.fixed(NOW, IST);
    ObjectMapper mapper = new ObjectMapper();
    OutboxPublisher publisher = new OutboxPublisher(outboxStore, mapper);
    StubNotificationDispatchClient notifications = new StubNotificationDispatchClient(publisher);
    RazorpayXPayoutPort razorpayx = new StubRazorpayXPayoutClient();

    commissionService =
        new AdminPharmacyCommissionService(
            pharmacies,
            commissionHistory,
            settlements,
            orderMetrics,
            profiles,
            audit,
            rateLimiter,
            clock);

    settlementService =
        new AdminPharmacySettlementService(
            pharmacies,
            settlements,
            profiles,
            razorpayx,
            notifications,
            rateLimiter,
            clock,
            mapper,
            WEBHOOK_SECRET);

    commissionApplyService = new CommissionApplyService(commissionHistory, pharmacies, clock);
    generationService =
        new SettlementGenerationService(pharmacies, settlements, orderMetrics, clock);

    pharmacies.put(samplePharmacy());
    profiles.putVerifiedBank(PID);
  }

  @Test
  void ac001_getCommission_includesCurrentPendingTcsPeriodBank() {
    commissionHistory.pending =
        new CommissionHistoryRow(
            Ids.newId(),
            PID,
            new BigDecimal("8.00"),
            new BigDecimal("7.00"),
            LocalDate.parse("2026-07-28"),
            "Loyalty incentive",
            null,
            FINANCE,
            NOW,
            null);
    orderMetrics.annualGmvYtd = 60_000_000L;
    orderMetrics.gmvCurrentPeriod = 18_500_000L;
    settlements.currentPeriod =
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "PENDING_RELEASE",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);

    Map<String, Object> data = commissionService.getCommission(finance(), PID);

    assertThat(data.get("current_commission_pct")).isEqualTo(new BigDecimal("8.00"));
    assertThat(data.get("pending_commission_change")).isNotNull();
    assertThat(data.get("tcs_applicable")).isEqualTo(true);
    assertThat(data.get("bank_account_masked")).isEqualTo("XXXXXXXXXXXX4321");
    assertThat(data.get("bank_account_verified")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> period = (Map<String, Object>) data.get("current_period");
    assertThat(period.get("gmv")).isEqualTo(new BigDecimal("185000.00"));
    assertThat(period.get("commission_earned")).isEqualTo(new BigDecimal("14800.00"));
    assertThat(period.get("tcs_deducted")).isEqualTo(new BigDecimal("1850.00"));
    assertThat(period.get("net_payable_to_pharmacy")).isEqualTo(new BigDecimal("168350.00"));
  }

  @Test
  void ac002_patchCommission25_returnsInvalidCommissionPct() {
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(),
                    PID,
                    new BigDecimal("25.00"),
                    LocalDate.parse("2026-07-28"),
                    "Too high",
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COMMISSION_PCT");
  }

  @Test
  void ac003_effectiveFromToday_returnsEffectiveFromMustBeFuture() {
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(),
                    PID,
                    new BigDecimal("7.00"),
                    LocalDate.parse("2026-07-24"),
                    "Today",
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EFFECTIVE_FROM_MUST_BE_FUTURE");
  }

  @Test
  void ac004_validChange_createsHistoryAndAuditLog() {
    Map<String, Object> data =
        commissionService.changeCommission(
            finance(),
            PID,
            new BigDecimal("7.00"),
            LocalDate.parse("2026-07-28"),
            "Loyalty incentive",
            "internal",
            "127.0.0.1");

    assertThat(data.get("commission_history_id")).isNotNull();
    assertThat(commissionHistory.inserted).hasSize(1);
    assertThat(audit.entries).hasSize(1);
    assertThat(audit.entries.getFirst().action()).isEqualTo("COMMISSION_CHANGED");
    assertThat(audit.entries.getFirst().payload()).containsKeys("before_value", "after_value");
    assertThat(audit.entries.getFirst().actorId()).isEqualTo(FINANCE);
  }

  @Test
  void ac005_tcsAboveThreshold_onePercent_belowZero() {
    var above =
        SettlementCalculator.compute(
            10_000_000L, new BigDecimal("8.00"), SettlementCalculator.TCS_THRESHOLD_PAISE + 1);
    assertThat(above.tcsApplicable()).isTrue();
    assertThat(above.tcsDeductedPaise()).isEqualTo(100_000L);

    var below = SettlementCalculator.compute(10_000_000L, new BigDecimal("8.00"), 10_000_000L);
    assertThat(below.tcsApplicable()).isTrue();
    assertThat(below.tcsDeductedPaise()).isEqualTo(100_000L);
    assertThat(below.netPaidPaise()).isEqualTo(9_100_000L);
  }

  @Test
  void ac006_releasePendingVerifiedBank_movesToReleasedAndQueuesNotify() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));

    Map<String, Object> data =
        settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-release-1");

    assertThat(data.get("status")).isEqualTo("RELEASED");
    assertThat(data.get("payout_initiated")).isEqualTo(true);
    assertThat(outboxStore.all()).isNotEmpty();

    String webhook =
        """
        {
          "event": "payout.processed",
          "payload": {
            "payout": {
              "entity": {
                "id": "%s",
                "status": "processed",
                "utr": "HDFC2026072212345678",
                "reference_id": "%s"
              }
            }
          }
        }
        """
            .formatted(settlements.byId.get(SETTLEMENT_ID).razorpayxPayoutId(), SETTLEMENT_ID);

    Map<String, Object> paid = signedWebhook(webhook);
    assertThat(paid.get("status")).isEqualTo("PAID");
    assertThat(settlements.byId.get(SETTLEMENT_ID).status()).isEqualTo("PAID");
    assertThat(outboxStore.all().size()).isGreaterThanOrEqualTo(2);

    String updatedEvent =
        """
        {"event":"payout.updated","payload":{"payout":{"entity":{"id":"%s","status":"processed","reference_id":"%s"}}}}
        """
            .formatted(settlements.byId.get(SETTLEMENT_ID).razorpayxPayoutId(), SETTLEMENT_ID);
    assertThat(signedWebhook(updatedEvent)).containsEntry("status", "PAID");

    String payoutIdOnly =
        "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\""
            + settlements.byId.get(SETTLEMENT_ID).razorpayxPayoutId()
            + "\",\"status\":\"processed\"}}}}";
    assertThat(signedWebhook(payoutIdOnly)).containsEntry("status", "PAID");
  }

  @Test
  void ac007_releaseHeld_returnsSettlementHeld() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("HELD"));

    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-2"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_HELD");
  }

  @Test
  void ac008_holdWithReason_setsHeldAndNotifies() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));

    Map<String, Object> data =
        settlementService.hold(finance(), PID, SETTLEMENT_ID, "Fraud review in progress");

    assertThat(data.get("status")).isEqualTo("HELD");
    assertThat(data.get("pharmacy_notified")).isEqualTo(true);
    assertThat(settlements.byId.get(SETTLEMENT_ID).status()).isEqualTo("HELD");
    assertThat(outboxStore.all()).isNotEmpty();
  }

  @Test
  void pendingChangeExists_returns409() {
    commissionHistory.pending =
        new CommissionHistoryRow(
            Ids.newId(),
            PID,
            new BigDecimal("8.00"),
            new BigDecimal("7.00"),
            LocalDate.parse("2026-07-28"),
            "existing",
            null,
            FINANCE,
            NOW,
            null);

    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(),
                    PID,
                    new BigDecimal("6.00"),
                    LocalDate.parse("2026-08-01"),
                    "Another",
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PENDING_CHANGE_EXISTS");
  }

  @Test
  void commissionApplyJob_updatesPharmacyPct() {
    UUID historyId = Ids.newId();
    commissionHistory.due.add(
        new CommissionHistoryRow(
            historyId,
            PID,
            new BigDecimal("8.00"),
            new BigDecimal("7.00"),
            LocalDate.parse("2026-07-24"),
            "apply",
            null,
            FINANCE,
            NOW,
            null));

    assertThat(commissionApplyService.applyDueChanges()).isEqualTo(1);
    assertThat(pharmacies.commissionPct).isEqualByComparingTo("7.00");
    assertThat(commissionHistory.applied).contains(historyId);
  }

  @Test
  void settlementGenerationJob_createsPendingRelease() {
    orderMetrics.gmvForPeriod = 5_000_000L;
    orderMetrics.annualGmvYtd = 60_000_000L;

    assertThat(generationService.generateWeeklySettlements()).isEqualTo(1);
    assertThat(settlements.inserted).hasSize(1);
    assertThat(settlements.inserted.getFirst().status()).isEqualTo("PENDING_RELEASE");
    LocalDate monday = SettlementPeriod.previousWeekMonday(LocalDate.parse("2026-07-24"));
    assertThat(settlements.inserted.getFirst().periodStart()).isEqualTo(monday);
  }

  @Test
  void releaseWithoutVerifiedBank_returns422() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    profiles.verified = false;

    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-3"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BANK_ACCOUNT_NOT_VERIFIED");
  }

  @Test
  void commissionGet_computedPeriodWhenNoSettlementRow() {
    orderMetrics.gmvForPeriod = 10_000_000L;
    orderMetrics.annualGmvYtd = 10_000_000L;
    settlements.currentPeriod = null;
    Map<String, Object> data = commissionService.getCommission(finance(), PID);
    @SuppressWarnings("unchecked")
    Map<String, Object> period = (Map<String, Object>) data.get("current_period");
    assertThat(period.get("gmv")).isEqualTo(new BigDecimal("100000.00"));
  }

  @Test
  void pharmacyNotFound() {
    assertThatThrownBy(() -> commissionService.getCommission(finance(), Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void releaseIdempotency_replaysWithoutDuplicatePayout() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-same");
    int outboxAfterFirst = outboxStore.all().size();

    Map<String, Object> replay =
        settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-same");
    assertThat(replay.get("status")).isEqualTo("RELEASED");
    assertThat(outboxStore.all()).hasSize(outboxAfterFirst);
  }

  @Test
  void listSettlements_returnsPagedHistory() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PAID"));
    var result = settlementService.listSettlements(finance(), PID, "ALL", null, null, 1, 50);
    assertThat(result.data().get("settlements")).asList().hasSize(1);
    assertThat(result.meta().total()).isEqualTo(1);
  }

  @Test
  void listSettlements_invalidStatus() {
    assertThatThrownBy(
            () -> settlementService.listSettlements(finance(), PID, "BAD", null, null, 1, 20))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
  }

  @Test
  void releaseAlreadyReleased_returns409() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("RELEASED"));
    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-released"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_ALREADY_RELEASED");
  }

  @Test
  void releaseSettlementNotFound_returns404() {
    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, Ids.newId(), null, "idem-missing"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_NOT_FOUND");
  }

  @Test
  void holdMissingReason_returns400() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(() -> settlementService.hold(finance(), PID, SETTLEMENT_ID, "  "))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
  }

  @Test
  void holdPaidSettlement_returns409() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PAID"));
    assertThatThrownBy(() -> settlementService.hold(finance(), PID, SETTLEMENT_ID, "too late"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_ALREADY_PAID");
  }

  @Test
  void webhook_ignoresUnknownEventsAndStatuses() {
    assertThat(signedWebhook("{\"event\":\"other\"}")).containsEntry("ignored", true);
    assertThat(
            signedWebhook(
                    "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"status\":\"pending\",\"id\":\"p1\"}}}}")
                .get("ignored"))
        .isEqualTo(true);
  }

  @Test
  void webhook_alreadyPaid_isIdempotent() {
    SettlementRow paid = pendingSettlement("PAID");
    settlements.byId.put(SETTLEMENT_ID, paid);
    settlements.byId.put(
        SETTLEMENT_ID,
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            paid.periodStart(),
            paid.periodEnd(),
            paid.gmvPaise(),
            paid.commissionPct(),
            paid.commissionEarnedPaise(),
            paid.tcsRatePct(),
            paid.tcsDeductedPaise(),
            paid.netPaidPaise(),
            "PAID",
            null,
            FINANCE,
            NOW,
            NOW,
            "pout_paid",
            "UTR",
            "url",
            null,
            NOW,
            NOW));
    Map<String, Object> result =
        signedWebhook(
            "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"pout_paid\",\"status\":\"processed\",\"reference_id\":\""
                + SETTLEMENT_ID
                + "\"}}}}");
    assertThat(result.get("status")).isEqualTo("PAID");
  }

  @Test
  void webhook_invalidJson_returns400() {
    assertThatThrownBy(() -> signedWebhook("not-json"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("WEBHOOK_INVALID");
  }

  @Test
  void commissionGet_withoutBankAccount() {
    profiles.verified = false;
    profiles.noBank = true;
    Map<String, Object> data = commissionService.getCommission(finance(), PID);
    assertThat(data.get("bank_account_masked")).isNull();
    assertThat(data.get("bank_account_verified")).isEqualTo(false);
  }

  @Test
  void commissionChange_reasonRequired() {
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(),
                    PID,
                    new BigDecimal("7.00"),
                    LocalDate.parse("2026-07-28"),
                    " ",
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
  }

  @Test
  void commissionForbiddenForOps() {
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    ops,
                    PID,
                    new BigDecimal("7.00"),
                    LocalDate.parse("2026-07-28"),
                    "nope",
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void settlementGeneration_skipsExistingPeriod() {
    LocalDate start = SettlementPeriod.previousWeekMonday(LocalDate.parse("2026-07-24"));
    LocalDate end = SettlementPeriod.previousWeekSunday(LocalDate.parse("2026-07-24"));
    settlements.insert(
        new SettlementRow(
            Ids.newId(),
            PID,
            start,
            end,
            1L,
            new BigDecimal("8.00"),
            0L,
            BigDecimal.ZERO,
            0L,
            1L,
            "PENDING_RELEASE",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    assertThat(generationService.generateWeeklySettlements()).isZero();
  }

  @Test
  void schedulers_invokeServices() {
    new CommissionApplyScheduler(commissionApplyService).applyDueCommissionChanges();
    new SettlementGenerationScheduler(generationService).generateWeeklySettlements();
  }

  @Test
  void pagedResult_nullDataUsesEmptyMap() {
    assertThat(new AdminPharmacySettlementService.PagedResult(null, null).data()).isEmpty();
  }

  @Test
  void rateLimitExceeded() {
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(() -> commissionService.getCommission(finance(), PID))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void commissionValidation_nullAndTooLongReason() {
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(), PID, null, LocalDate.parse("2026-07-28"), "ok", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COMMISSION_PCT");
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(), PID, new BigDecimal("7.00"), null, "ok", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EFFECTIVE_FROM_MUST_BE_FUTURE");
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(),
                    PID,
                    new BigDecimal("7.00"),
                    LocalDate.parse("2026-07-28"),
                    "x".repeat(501),
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
  }

  @Test
  void commissionUnauthorizedAndForbiddenRead() {
    assertThatThrownBy(() -> commissionService.getCommission(null, PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> commissionService.getCommission(support, PID))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void settlementUnauthorizedAndForbiddenFinance() {
    assertThatThrownBy(() -> settlementService.release(null, PID, SETTLEMENT_ID, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> settlementService.release(ops, PID, SETTLEMENT_ID, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void holdReasonTooLong_andSettlementNotFound() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(() -> settlementService.hold(finance(), PID, SETTLEMENT_ID, "x".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> settlementService.hold(finance(), PID, Ids.newId(), "reason"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_NOT_FOUND");
  }

  @Test
  void webhook_blankPayoutId_andMissingSettlement() {
    assertThat(
            signedWebhook(
                    "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"status\":\"processed\"}}}}")
                .get("ignored"))
        .isEqualTo(true);
    assertThatThrownBy(
            () ->
                signedWebhook(
                    "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"missing\",\"status\":\"processed\"}}}}"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_NOT_FOUND");
  }

  @Test
  void parseSettlementId_usesPayoutLookupWhenReferenceInvalid() {
    settlements.byId.put(
        SETTLEMENT_ID,
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "RELEASED",
            null,
            FINANCE,
            NOW,
            null,
            "pout_lookup",
            null,
            null,
            null,
            NOW,
            NOW));
    UUID resolved = settlementService.parseSettlementId("bad", "pout_lookup");
    assertThat(resolved).isEqualTo(SETTLEMENT_ID);
  }

  @Test
  void listSettlements_defaultStatusFilter() {
    var result = settlementService.listSettlements(finance(), PID, null, null, null, null, null);
    assertThat(result.data().get("settlements")).asList().isEmpty();
  }

  @Test
  void settlementGeneration_carryOnlyWhenZeroGmv() {
    orderMetrics.gmvForPeriod = 0L;
    settlements.byId.put(
        Ids.newId(),
        new SettlementRow(
            Ids.newId(),
            PID,
            LocalDate.parse("2026-07-07"),
            LocalDate.parse("2026-07-13"),
            0L,
            new BigDecimal("8.00"),
            0L,
            new BigDecimal("0.00"),
            0L,
            8_000L,
            "BELOW_THRESHOLD_CARRIED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    assertThat(generationService.generateWeeklySettlements()).isEqualTo(1);
    assertThat(settlements.inserted.getFirst().netPaidPaise()).isEqualTo(8_000L);
  }

  @Test
  void settlementGeneration_skipsZeroGmvWithoutCarry() {
    orderMetrics.gmvForPeriod = 0L;
    assertThat(generationService.generateWeeklySettlements()).isZero();
  }

  @Test
  void settlementGeneration_appliesCarryForward() {
    orderMetrics.gmvForPeriod = 1_000_000L;
    settlements.byId.put(
        Ids.newId(),
        new SettlementRow(
            Ids.newId(),
            PID,
            LocalDate.parse("2026-07-07"),
            LocalDate.parse("2026-07-13"),
            5_000L,
            new BigDecimal("8.00"),
            400L,
            new BigDecimal("0.00"),
            0L,
            5_000L,
            "BELOW_THRESHOLD_CARRIED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    assertThat(generationService.generateWeeklySettlements()).isEqualTo(1);
    assertThat(settlements.inserted.getFirst().netPaidPaise()).isGreaterThan(1_000_000L - 100_000L);
  }

  @Test
  void settlementGeneration_skipsMissingPharmacyDetail() {
    UUID ghost = Ids.newId();
    pharmacies.listActiveIdsOverride = List.of(PID, ghost);
    pharmacies.missingDetailId = ghost;
    orderMetrics.gmvForPeriod = 5_000_000L;
    assertThat(generationService.generateWeeklySettlements()).isEqualTo(1);
  }

  @Test
  void commissionPctBelowMin_returnsInvalidCommissionPct() {
    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(),
                    PID,
                    new BigDecimal("2.00"),
                    LocalDate.parse("2026-07-28"),
                    "Too low",
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COMMISSION_PCT");
  }

  @Test
  void commissionReadByOps_andChangeBySuper() {
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThat(commissionService.getCommission(ops, PID)).containsKey("pharmacy_id");

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Map<String, Object> changed =
        commissionService.changeCommission(
            superAdmin,
            PID,
            new BigDecimal("7.00"),
            LocalDate.parse("2026-07-28"),
            "Super change",
            "  ",
            "127.0.0.1");
    assertThat(changed.get("commission_history_id")).isNotNull();
    assertThat(commissionHistory.inserted.getFirst().notes()).isNull();
  }

  @Test
  void commissionLedgerFallbackWhenPeriodGmvZero() {
    settlements.currentPeriod = null;
    orderMetrics.gmvForPeriod = 0L;
    orderMetrics.gmvCurrentPeriod = 2_000_000L;
    orderMetrics.annualGmvYtd = 2_000_000L;

    Map<String, Object> data = commissionService.getCommission(finance(), PID);
    @SuppressWarnings("unchecked")
    Map<String, Object> period = (Map<String, Object>) data.get("current_period");
    assertThat(period.get("gmv")).isEqualTo(new BigDecimal("20000.00"));
  }

  @Test
  void commissionLastSettlementDate_handlesPaidAt() {
    settlements.latestPaid =
        new SettlementRow(
            Ids.newId(),
            PID,
            LocalDate.parse("2026-07-07"),
            LocalDate.parse("2026-07-13"),
            1L,
            new BigDecimal("8.00"),
            1L,
            BigDecimal.ZERO,
            0L,
            1L,
            "PAID",
            null,
            FINANCE,
            NOW,
            NOW,
            "pout",
            "UTR",
            "url",
            null,
            NOW,
            NOW);
    assertThat(commissionService.getCommission(finance(), PID).get("last_settlement_date"))
        .isEqualTo("2026-07-24");

    settlements.latestPaid =
        new SettlementRow(
            settlements.latestPaid.id(),
            PID,
            settlements.latestPaid.periodStart(),
            settlements.latestPaid.periodEnd(),
            1L,
            new BigDecimal("8.00"),
            1L,
            BigDecimal.ZERO,
            0L,
            1L,
            "PAID",
            null,
            FINANCE,
            NOW,
            null,
            "pout",
            "UTR",
            "url",
            null,
            NOW,
            NOW);
    assertThat(commissionService.getCommission(finance(), PID).get("last_settlement_date"))
        .isNull();
  }

  @Test
  void settlementListPaginationDefaults_andBlankStatus() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    var result = settlementService.listSettlements(finance(), PID, "  ", null, null, 0, 0);
    assertThat(result.meta().page()).isEqualTo(1);
    assertThat(result.meta().limit()).isEqualTo(20);
    @SuppressWarnings("unchecked")
    Map<String, Object> item =
        ((List<Map<String, Object>>) result.data().get("settlements")).getFirst();
    assertThat(item.get("released_at")).isNull();
    assertThat(item.get("paid_at")).isNull();
  }

  @Test
  void settlementListPharmacyNotFound() {
    assertThatThrownBy(
            () ->
                settlementService.listSettlements(finance(), Ids.newId(), "ALL", null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void settlementRateLimitExceeded() {
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(
            () -> settlementService.listSettlements(finance(), PID, "ALL", null, null, 1, 20))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void releaseMissingIdempotencyKey_returns400() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> settlementService.release(superAdmin, PID, SETTLEMENT_ID, "notes", "   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void releasePaid_returnsAlreadyReleased() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PAID"));
    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-paid"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_ALREADY_RELEASED");
  }

  @Test
  void releaseIdempotencyReplay_nullReleasedAt() {
    settlements.byIdempotency.put("idem-null", pendingSettlement("RELEASED"));
    Map<String, Object> replay =
        settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-null");
    assertThat(replay.get("released_at")).isNull();
  }

  @Test
  void holdNullReason_returns400() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(() -> settlementService.hold(finance(), PID, SETTLEMENT_ID, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
  }

  @Test
  void webhook_settlementNotFoundByReferenceId() {
    UUID missing = Ids.newId();
    String webhook =
        """
        {"event":"payout.processed","payload":{"payout":{"entity":{"id":"pout_x","status":"processed","reference_id":"%s"}}}}
        """
            .formatted(missing);
    assertThatThrownBy(() -> signedWebhook(webhook, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
    assertThatThrownBy(() -> signedWebhook(webhook, "deadbeef"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
    assertThatThrownBy(() -> signedWebhook(webhook))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_NOT_FOUND");
  }

  @Test
  void webhook_pendingRelease_isIgnored() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    String webhook =
        """
        {"event":"payout.processed","payload":{"payout":{"entity":{"id":"pout_x","status":"processed","reference_id":"%s"}}}}
        """
            .formatted(SETTLEMENT_ID);
    assertThat(signedWebhook(webhook)).containsEntry("ignored", true);
  }

  @Test
  void webhook_processedWithoutUtr_setsEmptyUtr() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-no-utr");
    String payoutId = settlements.byId.get(SETTLEMENT_ID).razorpayxPayoutId();
    String webhook =
        """
        {"event":"payout.processed","payload":{"payout":{"entity":{"id":"%s","status":"processed","reference_id":"%s"}}}}
        """
            .formatted(payoutId, SETTLEMENT_ID);
    Map<String, Object> paid = signedWebhook(webhook);
    assertThat(paid.get("utr_number")).isEqualTo("");
    assertThat(paid.get("status")).isEqualTo("PAID");
  }

  @Test
  void settlementBranches_remainingCoverage() {
    settlements.byId.put(
        SETTLEMENT_ID,
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "PAID",
            null,
            FINANCE,
            NOW,
            NOW,
            "pout_ts",
            "UTR",
            "url",
            null,
            NOW,
            NOW));
    var listed =
        settlementService.listSettlements(
            finance(),
            PID,
            "ALL",
            LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-31"),
            1,
            20);
    @SuppressWarnings("unchecked")
    Map<String, Object> item =
        ((List<Map<String, Object>>) listed.data().get("settlements")).getFirst();
    assertThat(item.get("released_at")).isNotNull();
    assertThat(item.get("paid_at")).isNotNull();

    assertThat(settlementService.parseSettlementId(null, "pout_ts")).isEqualTo(SETTLEMENT_ID);
    assertThat(settlementService.parseSettlementId("   ", "pout_ts")).isEqualTo(SETTLEMENT_ID);

    assertThat(
            signedWebhook(
                    "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"  \",\"status\":\"processed\"}}}}")
                .get("ignored"))
        .isEqualTo(true);

    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(() -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    settlementService.release(finance(), PID, SETTLEMENT_ID, null, "  idem-trim  ");

    String webhookWithNullUtr =
        """
        {"event":"payout.processed","payload":{"payout":{"entity":{"id":"pout_ts","status":"processed","utr":null,"reference_id":"%s"}}}}
        """
            .formatted(SETTLEMENT_ID);
    settlements.byId.put(
        SETTLEMENT_ID,
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "RELEASED",
            null,
            FINANCE,
            NOW,
            null,
            "pout_ts",
            null,
            null,
            "  idem-trim  ",
            NOW,
            NOW));
    assertThat(signedWebhook(webhookWithNullUtr).get("status")).isEqualTo("PAID");

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(
            () -> settlementService.release(superAdmin, PID, SETTLEMENT_ID, "  notes  ", "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void commissionBranches_remainingCoverage() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThat(commissionService.getCommission(superAdmin, PID)).containsKey("pharmacy_id");

    assertThatThrownBy(
            () ->
                commissionService.changeCommission(
                    finance(),
                    PID,
                    new BigDecimal("7.00"),
                    LocalDate.parse("2026-07-28"),
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");

    commissionService.changeCommission(
        finance(),
        PID,
        new BigDecimal("7.00"),
        LocalDate.parse("2026-07-28"),
        "valid reason",
        "  internal  ",
        null);
    assertThat(commissionHistory.inserted.getFirst().notes()).isEqualTo("internal");

    commissionHistory.pending = null;
    commissionHistory.inserted.clear();
    commissionService.changeCommission(
        finance(),
        PID,
        new BigDecimal("7.50"),
        LocalDate.parse("2026-08-01"),
        "notes blank",
        "  ",
        null);
    assertThat(commissionHistory.inserted.getFirst().notes()).isNull();

    commissionHistory.pending = null;
    commissionHistory.inserted.clear();
    commissionService.changeCommission(
        finance(),
        PID,
        new BigDecimal("7.25"),
        LocalDate.parse("2026-08-02"),
        "notes null",
        null,
        null);
    assertThat(commissionHistory.inserted.getFirst().notes()).isNull();
  }

  @Test
  void webhookSecretGuard_deployedProfileValidation() {
    AdminPharmacySettlementService.validateWebhookSecretForDeployedProfile(
        AdminPharmacySettlementService.LOCAL_WEBHOOK_SECRET, false);
    AdminPharmacySettlementService.validateWebhookSecretForDeployedProfile("", false);
    AdminPharmacySettlementService.validateWebhookSecretForDeployedProfile(
        "prod-razorpayx-hmac-secret", true);
    assertThatThrownBy(
            () -> AdminPharmacySettlementService.validateWebhookSecretForDeployedProfile("", true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AdminPharmacySettlementService.validateWebhookSecretForDeployedProfile(
                    AdminPharmacySettlementService.LOCAL_WEBHOOK_SECRET, true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void releaseIdempotencyKeyTooLong_returns400() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "k".repeat(129)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void releaseIdempotencyKeyConflict_returns409() {
    UUID otherSettlement = Ids.newId();
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    SettlementRow other =
        new SettlementRow(
            otherSettlement,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "RELEASED",
            null,
            FINANCE,
            NOW,
            null,
            "pout_other",
            null,
            null,
            "shared-key",
            NOW,
            NOW);
    settlements.byId.put(otherSettlement, other);
    settlements.byIdempotency.put("shared-key", other);
    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "shared-key"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
  }

  @Test
  void webhook_acceptsSha256PrefixSignature() {
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("RELEASED"));
    settlements.byId.put(
        SETTLEMENT_ID,
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "RELEASED",
            null,
            FINANCE,
            NOW,
            null,
            "pout_prefix",
            null,
            null,
            null,
            NOW,
            NOW));
    String body =
        "{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{\"id\":\"pout_prefix\",\"status\":\"processed\",\"reference_id\":\""
            + SETTLEMENT_ID
            + "\"}}}}";
    String sig = "sha256=" + signWebhook(body);
    assertThat(signedWebhook(body, sig).get("status")).isEqualTo("PAID");
  }

  @Test
  void releaseRemainingBranches_coverage() {
    AdminPharmacySettlementService nullSecretService =
        new AdminPharmacySettlementService(
            pharmacies,
            settlements,
            profiles,
            new StubRazorpayXPayoutClient(),
            new StubNotificationDispatchClient(
                new OutboxPublisher(outboxStore, new ObjectMapper())),
            rateLimiter,
            Clock.fixed(NOW, IST),
            new ObjectMapper(),
            null);
    assertThat(nullSecretService).isNotNull();
    assertThatThrownBy(
            () ->
                AdminPharmacySettlementService.validateWebhookSecretForDeployedProfile(null, true))
        .isInstanceOf(IllegalStateException.class);

    SettlementRow failed =
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "FAILED",
            null,
            FINANCE,
            NOW,
            null,
            null,
            null,
            null,
            "idem-failed",
            NOW,
            NOW);
    settlements.byId.put(SETTLEMENT_ID, failed);
    settlements.byIdempotency.put("idem-failed", failed);
    Map<String, Object> failedReplay =
        settlementService.release(finance(), PID, SETTLEMENT_ID, null, "idem-failed");
    assertThat(failedReplay.get("payout_initiated")).isEqualTo(false);

    settlements.byId.put(
        SETTLEMENT_ID,
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "PENDING_RELEASE",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "other-claim",
            NOW,
            NOW));
    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "fresh-key"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_CONFLICT");

    ClaimFinalizeSettlementStore guarded = new ClaimFinalizeSettlementStore(settlements);
    AdminPharmacySettlementService guardedService =
        new AdminPharmacySettlementService(
            pharmacies,
            guarded,
            profiles,
            new StubRazorpayXPayoutClient(),
            new StubNotificationDispatchClient(
                new OutboxPublisher(outboxStore, new ObjectMapper())),
            rateLimiter,
            Clock.fixed(NOW, IST),
            new ObjectMapper(),
            WEBHOOK_SECRET);
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(
            () -> guardedService.release(finance(), PID, SETTLEMENT_ID, null, "finalize-fail"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_CONFLICT");

    RazorpayXPayoutPort failingPayout =
        request -> {
          throw new IllegalStateException("payout down");
        };
    AdminPharmacySettlementService payoutFailService =
        new AdminPharmacySettlementService(
            pharmacies,
            settlements,
            profiles,
            failingPayout,
            new StubNotificationDispatchClient(
                new OutboxPublisher(outboxStore, new ObjectMapper())),
            rateLimiter,
            Clock.fixed(NOW, IST),
            new ObjectMapper(),
            WEBHOOK_SECRET);
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    assertThatThrownBy(
            () -> payoutFailService.release(finance(), PID, SETTLEMENT_ID, null, "payout-fail"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYOUT_FAILED");
    assertThat(settlements.byId.get(SETTLEMENT_ID).status()).isEqualTo("FAILED");

    assertThatThrownBy(() -> signedWebhook("{}", "   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
    assertThatThrownBy(() -> signedWebhook("{}", "abc"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");

    UUID otherPharmacy = Ids.newId();
    SettlementRow otherPharmacyRow =
        new SettlementRow(
            SETTLEMENT_ID,
            otherPharmacy,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "RELEASED",
            null,
            FINANCE,
            NOW,
            null,
            "pout_x",
            null,
            null,
            "pharmacy-conflict",
            NOW,
            NOW);
    settlements.byIdempotency.put("pharmacy-conflict", otherPharmacyRow);
    assertThatThrownBy(
            () ->
                settlementService.release(finance(), PID, SETTLEMENT_ID, null, "pharmacy-conflict"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    settlements.byId.put(
        SETTLEMENT_ID,
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "PENDING_RELEASE",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "race-key",
            NOW,
            NOW));
    settlements.byIdempotency.put(
        "race-key",
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "RELEASED",
            null,
            FINANCE,
            NOW,
            null,
            "pout_race",
            null,
            null,
            "race-key",
            NOW,
            NOW));
    Map<String, Object> raceReplay =
        settlementService.release(finance(), PID, SETTLEMENT_ID, null, "race-key");
    assertThat(raceReplay.get("status")).isEqualTo("RELEASED");

    ReplayAfterClaimStore replayStore = new ReplayAfterClaimStore(settlements);
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    settlements.byIdempotency.put(
        "late-replay",
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "RELEASED",
            null,
            FINANCE,
            NOW,
            null,
            "pout_late",
            null,
            null,
            "late-replay",
            NOW,
            NOW));
    AdminPharmacySettlementService replayService =
        new AdminPharmacySettlementService(
            pharmacies,
            replayStore,
            profiles,
            new StubRazorpayXPayoutClient(),
            new StubNotificationDispatchClient(
                new OutboxPublisher(outboxStore, new ObjectMapper())),
            rateLimiter,
            Clock.fixed(NOW, IST),
            new ObjectMapper(),
            WEBHOOK_SECRET);
    assertThat(
            replayService.release(finance(), PID, SETTLEMENT_ID, null, "late-replay").get("status"))
        .isEqualTo("RELEASED");

    settlements.byIdempotency.put(
        "late-failed",
        new SettlementRow(
            SETTLEMENT_ID,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            18_500_000L,
            new BigDecimal("8.00"),
            1_480_000L,
            new BigDecimal("1.00"),
            185_000L,
            16_835_000L,
            "FAILED",
            null,
            FINANCE,
            NOW,
            null,
            null,
            null,
            null,
            "late-failed",
            NOW,
            NOW));
    ReplayAfterClaimStore failedReplayStore = new ReplayAfterClaimStore(settlements);
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    AdminPharmacySettlementService failedReplayService =
        new AdminPharmacySettlementService(
            pharmacies,
            failedReplayStore,
            profiles,
            new StubRazorpayXPayoutClient(),
            new StubNotificationDispatchClient(
                new OutboxPublisher(outboxStore, new ObjectMapper())),
            rateLimiter,
            Clock.fixed(NOW, IST),
            new ObjectMapper(),
            WEBHOOK_SECRET);
    Map<String, Object> failedLateReplay =
        failedReplayService.release(finance(), PID, SETTLEMENT_ID, null, "late-failed");
    assertThat(failedLateReplay.get("payout_initiated")).isEqualTo(false);

    VanishingSettlementStore vanishing = new VanishingSettlementStore(settlements);
    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("PENDING_RELEASE"));
    AdminPharmacySettlementService vanishingService =
        new AdminPharmacySettlementService(
            pharmacies,
            vanishing,
            profiles,
            new StubRazorpayXPayoutClient(),
            new StubNotificationDispatchClient(
                new OutboxPublisher(outboxStore, new ObjectMapper())),
            rateLimiter,
            Clock.fixed(NOW, IST),
            new ObjectMapper(),
            WEBHOOK_SECRET);
    assertThatThrownBy(
            () -> vanishingService.release(finance(), PID, SETTLEMENT_ID, null, "vanished"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_NOT_FOUND");

    settlements.byId.put(SETTLEMENT_ID, pendingSettlement("HELD"));
    assertThatThrownBy(
            () -> settlementService.release(finance(), PID, SETTLEMENT_ID, null, "held-key"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_HELD");

    String validLenSig = "f".repeat(64);
    assertThatThrownBy(() -> signedWebhook("{}", validLenSig))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
  }

  private Map<String, Object> signedWebhook(String body) {
    return signedWebhook(body, signWebhook(body));
  }

  private Map<String, Object> signedWebhook(String body, String signature) {
    return settlementService.handlePayoutWebhook(signature, body.getBytes(StandardCharsets.UTF_8));
  }

  private String signWebhook(String body) {
    return AutoKycService.hmacSha256Hex(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
  }

  private SettlementRow pendingSettlement(String status) {
    return new SettlementRow(
        SETTLEMENT_ID,
        PID,
        LocalDate.parse("2026-07-14"),
        LocalDate.parse("2026-07-20"),
        18_500_000L,
        new BigDecimal("8.00"),
        1_480_000L,
        new BigDecimal("1.00"),
        185_000L,
        16_835_000L,
        status,
        "HELD".equals(status) ? "held" : null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private static AdminDetailRow samplePharmacy() {
    return new AdminDetailRow(
        PID,
        "PH-001",
        "Sharma Medical Store",
        "Owner",
        "+919999999999",
        "owner@example.com",
        "RETAIL",
        Map.of(),
        null,
        null,
        null,
        null,
        "ACTIVE",
        "BASIC",
        new BigDecimal("8.00"),
        null,
        null,
        true,
        true,
        NOW,
        NOW,
        NOW,
        null,
        null,
        null,
        NOW,
        null,
        null,
        null);
  }

  private static MedmatePrincipal finance() {
    return new MedmatePrincipal(FINANCE, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  }

  static final class FakePharmacyStore implements AdminPharmacyStore {
    final Map<UUID, AdminDetailRow> rows = new ConcurrentHashMap<>();
    BigDecimal commissionPct = new BigDecimal("8.00");
    List<UUID> listActiveIdsOverride;
    UUID missingDetailId;

    void put(AdminDetailRow row) {
      rows.put(row.pharmacyId(), row);
    }

    @Override
    public Optional<AdminDetailRow> findDetail(UUID pharmacyId) {
      if (pharmacyId.equals(missingDetailId)) {
        return Optional.empty();
      }
      return Optional.ofNullable(rows.get(pharmacyId));
    }

    @Override
    public void updateCommissionPct(UUID pharmacyId, BigDecimal commissionPct, Instant updatedAt) {
      this.commissionPct = commissionPct;
      AdminDetailRow old = rows.get(pharmacyId);
      if (old != null) {
        rows.put(
            pharmacyId,
            new AdminDetailRow(
                old.pharmacyId(),
                old.code(),
                old.businessName(),
                old.ownerName(),
                old.phone(),
                old.email(),
                old.businessType(),
                old.address(),
                old.gstin(),
                old.drugLicenceNumber(),
                old.fssaiNumber(),
                old.panNumber(),
                old.status(),
                old.plan(),
                commissionPct,
                old.zoneId(),
                old.zoneName(),
                old.online(),
                old.canReapply(),
                old.kycSubmittedAt(),
                old.createdAt(),
                old.updatedAt(),
                old.planExpiresAt(),
                old.rejectionReason(),
                old.rejectionDetails(),
                old.activatedAt(),
                old.suspendedAt(),
                old.suspendType(),
                old.kycSlaResetAt()));
      }
    }

    @Override
    public List<UUID> listActivePharmacyIds() {
      return listActiveIdsOverride == null ? List.copyOf(rows.keySet()) : listActiveIdsOverride;
    }

    @Override
    public List<AdminListRow> listByIds(List<UUID> pharmacyIds) {
      return List.of();
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(List.of(), 0);
    }

    @Override
    public List<AdminListRow> exportRows(ListFilter filter) {
      return List.of();
    }

    @Override
    public DirectorySummary directorySummary(Instant asOf) {
      return new DirectorySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, asOf);
    }

    @Override
    public Map<String, String> documentStatusSummary(UUID pharmacyId) {
      return Map.of();
    }

    @Override
    public String nextCode() {
      return "PH-002";
    }

    @Override
    public void approve(
        UUID pharmacyId,
        BigDecimal commissionPct,
        UUID zoneId,
        Instant activatedAt,
        Instant updatedAt) {}

    @Override
    public void reject(
        UUID pharmacyId,
        String rejectionReason,
        String rejectionDetails,
        boolean canReapply,
        Instant rejectedAt) {}

    @Override
    public void suspend(
        UUID pharmacyId, String suspendType, boolean canReapply, Instant suspendedAt) {}

    @Override
    public void reactivate(UUID pharmacyId, Instant reactivatedAt, boolean canReapply) {}

    @Override
    public void resetKycSla(UUID pharmacyId, Instant slaResetAt) {}
  }

  static final class FakeCommissionHistory implements CommissionHistoryStore {
    CommissionHistoryRow pending;
    final List<CommissionHistoryRow> inserted = new ArrayList<>();
    final List<CommissionHistoryRow> due = new ArrayList<>();
    final List<UUID> applied = new ArrayList<>();

    @Override
    public Optional<CommissionHistoryRow> findPendingChange(UUID pharmacyId) {
      return Optional.ofNullable(pending);
    }

    @Override
    public void insert(CommissionHistoryRow row) {
      inserted.add(row);
      pending = row;
    }

    @Override
    public List<CommissionHistoryRow> findDueForApply(LocalDate effectiveDate) {
      return List.copyOf(due);
    }

    @Override
    public void markApplied(UUID id, Instant appliedAt) {
      applied.add(id);
    }
  }

  static final class FakeSettlementStore implements SettlementStore {
    final Map<UUID, SettlementRow> byId = new ConcurrentHashMap<>();
    final Map<String, SettlementRow> byIdempotency = new ConcurrentHashMap<>();
    SettlementRow currentPeriod;
    SettlementRow latestPaid;
    final List<SettlementRow> inserted = new ArrayList<>();

    @Override
    public Optional<SettlementRow> findById(UUID settlementId) {
      return Optional.ofNullable(byId.get(settlementId));
    }

    @Override
    public Optional<SettlementRow> findByIdForPharmacy(UUID pharmacyId, UUID settlementId) {
      SettlementRow row = byId.get(settlementId);
      return row != null && row.pharmacyId().equals(pharmacyId)
          ? Optional.of(row)
          : Optional.empty();
    }

    @Override
    public Optional<SettlementRow> findByIdempotencyKey(String idempotencyKey) {
      return Optional.ofNullable(byIdempotency.get(idempotencyKey));
    }

    @Override
    public Optional<SettlementRow> findByRazorpayxPayoutId(String razorpayxPayoutId) {
      return byId.values().stream()
          .filter(r -> razorpayxPayoutId.equals(r.razorpayxPayoutId()))
          .findFirst();
    }

    @Override
    public Optional<SettlementRow> findForPeriod(
        UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return Optional.ofNullable(currentPeriod);
    }

    @Override
    public Optional<SettlementRow> findLatestPaid(UUID pharmacyId) {
      return Optional.ofNullable(latestPaid);
    }

    @Override
    public void insert(SettlementRow row) {
      inserted.add(row);
      byId.put(row.id(), row);
    }

    @Override
    public boolean claimForRelease(
        UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant updatedAt) {
      SettlementRow old = byId.get(settlementId);
      if (old == null || !old.pharmacyId().equals(pharmacyId)) {
        return false;
      }
      if (!"PENDING_RELEASE".equals(old.status()) || old.releaseIdempotencyKey() != null) {
        return false;
      }
      SettlementRow claimed =
          new SettlementRow(
              old.id(),
              old.pharmacyId(),
              old.periodStart(),
              old.periodEnd(),
              old.gmvPaise(),
              old.commissionPct(),
              old.commissionEarnedPaise(),
              old.tcsRatePct(),
              old.tcsDeductedPaise(),
              old.netPaidPaise(),
              old.status(),
              old.holdReason(),
              old.releasedBy(),
              old.releasedAt(),
              old.paidAt(),
              old.razorpayxPayoutId(),
              old.utrNumber(),
              old.receiptUrl(),
              idempotencyKey,
              old.createdAt(),
              updatedAt);
      byId.put(settlementId, claimed);
      byIdempotency.put(idempotencyKey, claimed);
      return true;
    }

    @Override
    public boolean finalizeRelease(
        UUID settlementId,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      SettlementRow old = byId.get(settlementId);
      if (old == null
          || !"PENDING_RELEASE".equals(old.status())
          || !idempotencyKey.equals(old.releaseIdempotencyKey())) {
        return false;
      }
      SettlementRow updated =
          new SettlementRow(
              old.id(),
              old.pharmacyId(),
              old.periodStart(),
              old.periodEnd(),
              old.gmvPaise(),
              old.commissionPct(),
              old.commissionEarnedPaise(),
              old.tcsRatePct(),
              old.tcsDeductedPaise(),
              old.netPaidPaise(),
              "RELEASED",
              old.holdReason(),
              releasedBy,
              releasedAt,
              old.paidAt(),
              razorpayxPayoutId,
              old.utrNumber(),
              old.receiptUrl(),
              idempotencyKey,
              old.createdAt(),
              updatedAt);
      byId.put(settlementId, updated);
      byIdempotency.put(idempotencyKey, updated);
      return true;
    }

    @Override
    public boolean markReleaseFailed(UUID settlementId, String idempotencyKey, Instant updatedAt) {
      SettlementRow old = byId.get(settlementId);
      if (old == null || !idempotencyKey.equals(old.releaseIdempotencyKey())) {
        return false;
      }
      SettlementRow updated =
          new SettlementRow(
              old.id(),
              old.pharmacyId(),
              old.periodStart(),
              old.periodEnd(),
              old.gmvPaise(),
              old.commissionPct(),
              old.commissionEarnedPaise(),
              old.tcsRatePct(),
              old.tcsDeductedPaise(),
              old.netPaidPaise(),
              "FAILED",
              old.holdReason(),
              old.releasedBy(),
              old.releasedAt(),
              old.paidAt(),
              old.razorpayxPayoutId(),
              old.utrNumber(),
              old.receiptUrl(),
              idempotencyKey,
              old.createdAt(),
              updatedAt);
      byId.put(settlementId, updated);
      byIdempotency.put(idempotencyKey, updated);
      return true;
    }

    @Override
    public void updateReleased(
        UUID settlementId,
        String status,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      SettlementRow old = byId.get(settlementId);
      SettlementRow updated =
          new SettlementRow(
              old.id(),
              old.pharmacyId(),
              old.periodStart(),
              old.periodEnd(),
              old.gmvPaise(),
              old.commissionPct(),
              old.commissionEarnedPaise(),
              old.tcsRatePct(),
              old.tcsDeductedPaise(),
              old.netPaidPaise(),
              status,
              old.holdReason(),
              releasedBy,
              releasedAt,
              old.paidAt(),
              razorpayxPayoutId,
              old.utrNumber(),
              old.receiptUrl(),
              idempotencyKey,
              old.createdAt(),
              updatedAt);
      byId.put(settlementId, updated);
      if (idempotencyKey != null) {
        byIdempotency.put(idempotencyKey, updated);
      }
    }

    @Override
    public void updateHeld(UUID settlementId, String reason, Instant updatedAt) {
      SettlementRow old = byId.get(settlementId);
      byId.put(
          settlementId,
          new SettlementRow(
              old.id(),
              old.pharmacyId(),
              old.periodStart(),
              old.periodEnd(),
              old.gmvPaise(),
              old.commissionPct(),
              old.commissionEarnedPaise(),
              old.tcsRatePct(),
              old.tcsDeductedPaise(),
              old.netPaidPaise(),
              "HELD",
              reason,
              old.releasedBy(),
              old.releasedAt(),
              old.paidAt(),
              old.razorpayxPayoutId(),
              old.utrNumber(),
              old.receiptUrl(),
              old.releaseIdempotencyKey(),
              old.createdAt(),
              updatedAt));
    }

    @Override
    public void updatePaid(
        UUID settlementId, String utrNumber, String receiptUrl, Instant paidAt, Instant updatedAt) {
      SettlementRow old = byId.get(settlementId);
      if (old == null || !"RELEASED".equals(old.status())) {
        return;
      }
      byId.put(
          settlementId,
          new SettlementRow(
              old.id(),
              old.pharmacyId(),
              old.periodStart(),
              old.periodEnd(),
              old.gmvPaise(),
              old.commissionPct(),
              old.commissionEarnedPaise(),
              old.tcsRatePct(),
              old.tcsDeductedPaise(),
              old.netPaidPaise(),
              "PAID",
              old.holdReason(),
              old.releasedBy(),
              old.releasedAt(),
              paidAt,
              old.razorpayxPayoutId(),
              utrNumber,
              receiptUrl,
              old.releaseIdempotencyKey(),
              old.createdAt(),
              updatedAt));
    }

    @Override
    public ListResult list(UUID pharmacyId, ListFilter filter) {
      return new ListResult(List.copyOf(byId.values()), byId.size());
    }

    @Override
    public boolean existsForPeriod(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return byId.values().stream()
          .anyMatch(
              r ->
                  r.pharmacyId().equals(pharmacyId)
                      && r.periodStart().equals(periodStart)
                      && r.periodEnd().equals(periodEnd));
    }

    @Override
    public long sumUnconsumedCarryForwardPaise(UUID pharmacyId) {
      return byId.values().stream()
          .filter(
              r ->
                  r.pharmacyId().equals(pharmacyId) && "BELOW_THRESHOLD_CARRIED".equals(r.status()))
          .mapToLong(SettlementRow::netPaidPaise)
          .sum();
    }

    @Override
    public void markCarryForwardConsumed(UUID pharmacyId, Instant consumedAt) {
      // ponytail: fake store drops carried rows so sum becomes 0
      byId.entrySet()
          .removeIf(
              e ->
                  e.getValue().pharmacyId().equals(pharmacyId)
                      && "BELOW_THRESHOLD_CARRIED".equals(e.getValue().status()));
    }
  }

  static final class SeedableOrderMetrics implements PharmacyOrderMetricsPort {
    long annualGmvYtd;
    long gmvCurrentPeriod;
    long gmvForPeriod;

    @Override
    public Performance performance(UUID pharmacyId) {
      return new Performance(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0L);
    }

    @Override
    public CommissionLedger commissionLedger(UUID pharmacyId) {
      return new CommissionLedger(gmvCurrentPeriod, 0L, 0L, 0L, null, null);
    }

    @Override
    public List<RecentOrder> recentOrders(UUID pharmacyId, int limit) {
      return List.of();
    }

    @Override
    public PeriodMetrics periodMetrics(UUID pharmacyId, LocalDate periodEnd, int days) {
      return new PeriodMetrics(
          0,
          0,
          0,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          0,
          BigDecimal.ZERO,
          0,
          0L,
          (short) 0);
    }

    @Override
    public RatingListResult listRatings(
        UUID pharmacyId, Integer ratingFilter, String sort, String order, int limit, int offset) {
      return new RatingListResult(BigDecimal.ZERO, 0, Map.of(), List.of(), 0L);
    }

    @Override
    public OrderListResult listOrders(
        UUID pharmacyId,
        String status,
        LocalDate fromDate,
        LocalDate toDate,
        int limit,
        int offset) {
      return new OrderListResult(List.of(), 0L);
    }

    @Override
    public long annualGmvYtdPaise(UUID pharmacyId) {
      return annualGmvYtd;
    }

    @Override
    public long gmvForPeriodPaise(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return gmvForPeriod;
    }
  }

  static final class FakeProfileStore implements PharmacyProfileStore {
    boolean verified = true;
    boolean noBank = false;

    void putVerifiedBank(UUID pharmacyId) {
      // marker only
    }

    @Override
    public Optional<BankAccountRecord> findActiveBankAccount(UUID pharmacyId) {
      if (noBank) {
        return Optional.empty();
      }
      if (!verified) {
        return Optional.of(
            new BankAccountRecord(
                Ids.newId(),
                pharmacyId,
                "Holder",
                "HDFC",
                "enc",
                "4321",
                "HDFC0001234",
                "CURRENT",
                "PENDING",
                null,
                null,
                NOW,
                NOW));
      }
      return Optional.of(
          new BankAccountRecord(
              Ids.newId(),
              pharmacyId,
              "Holder",
              "HDFC",
              "enc",
              "4321",
              "HDFC0001234",
              "CURRENT",
              "VERIFIED",
              "pd-ref",
              NOW,
              NOW,
              NOW));
    }

    @Override
    public Optional<ProfileRecord> findById(UUID pharmacyId) {
      return Optional.empty();
    }

    @Override
    public void updateProfileFields(
        UUID pharmacyId,
        String tagline,
        String logoUrl,
        Map<String, Object> address,
        Instant updatedAt) {}

    @Override
    public void setPendingPhone(UUID pharmacyId, String pendingPhone, Instant updatedAt) {}

    @Override
    public void setPendingEmail(UUID pharmacyId, String pendingEmail, Instant updatedAt) {}

    @Override
    public void applyPhone(UUID pharmacyId, String phone, Instant updatedAt) {}

    @Override
    public void applyEmail(UUID pharmacyId, String email, Instant updatedAt) {}

    @Override
    public void updateTaxFields(
        UUID pharmacyId,
        String gstin,
        String panNumber,
        String drugLicenceNumber,
        String fssaiNumber,
        Boolean isGstRegistered,
        Boolean eInvoicingEnabled,
        Boolean tdsApplicable,
        Boolean tcsApplicable,
        String registeredPharmacistName,
        boolean gstinReverificationPending,
        Instant updatedAt) {}

    @Override
    public void replaceOperatingHours(
        UUID pharmacyId, List<OperatingHoursRecord> hours, Instant updatedAt) {}

    @Override
    public List<OperatingHoursRecord> listOperatingHours(UUID pharmacyId) {
      return List.of();
    }

    @Override
    public void softDeleteBankAccount(UUID bankAccountId, Instant deletedAt) {}

    @Override
    public void insertBankAccount(BankAccountRecord record) {}

    @Override
    public void updateBankVerification(
        UUID bankAccountId,
        String verificationStatus,
        String pennyDropReference,
        Instant verifiedAt,
        Instant updatedAt) {}

    @Override
    public List<BankAccountRecord> findStalePendingBankAccounts(Instant createdBefore, int limit) {
      return List.of();
    }

    @Override
    public void updateBusinessName(UUID pharmacyId, String businessName, Instant updatedAt) {}

    @Override
    public void updateTagline(UUID pharmacyId, String tagline, Instant updatedAt) {}

    @Override
    public void updateLogoUrl(UUID pharmacyId, String logoUrl, Instant updatedAt) {}

    @Override
    public void updateAddress(UUID pharmacyId, Map<String, Object> address, Instant updatedAt) {}

    @Override
    public void updatePhone(UUID pharmacyId, String phone, Instant updatedAt) {}

    @Override
    public void updateEmail(UUID pharmacyId, String email, Instant updatedAt) {}
  }

  static final class VanishingSettlementStore implements SettlementStore {
    private final FakeSettlementStore delegate;
    private final AtomicBoolean firstFind = new AtomicBoolean(true);

    VanishingSettlementStore(FakeSettlementStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<SettlementRow> findById(UUID settlementId) {
      return delegate.findById(settlementId);
    }

    @Override
    public Optional<SettlementRow> findByIdForPharmacy(UUID pharmacyId, UUID settlementId) {
      if (firstFind.compareAndSet(true, false)) {
        return delegate.findByIdForPharmacy(pharmacyId, settlementId);
      }
      return Optional.empty();
    }

    @Override
    public Optional<SettlementRow> findByIdempotencyKey(String idempotencyKey) {
      return delegate.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public Optional<SettlementRow> findByRazorpayxPayoutId(String razorpayxPayoutId) {
      return delegate.findByRazorpayxPayoutId(razorpayxPayoutId);
    }

    @Override
    public Optional<SettlementRow> findForPeriod(
        UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return delegate.findForPeriod(pharmacyId, periodStart, periodEnd);
    }

    @Override
    public Optional<SettlementRow> findLatestPaid(UUID pharmacyId) {
      return delegate.findLatestPaid(pharmacyId);
    }

    @Override
    public void insert(SettlementRow row) {
      delegate.insert(row);
    }

    @Override
    public void updateReleased(
        UUID settlementId,
        String status,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      delegate.updateReleased(
          settlementId,
          status,
          releasedBy,
          releasedAt,
          razorpayxPayoutId,
          idempotencyKey,
          updatedAt);
    }

    @Override
    public boolean claimForRelease(
        UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant updatedAt) {
      return false;
    }

    @Override
    public boolean finalizeRelease(
        UUID settlementId,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      return delegate.finalizeRelease(
          settlementId, releasedBy, releasedAt, razorpayxPayoutId, idempotencyKey, updatedAt);
    }

    @Override
    public boolean markReleaseFailed(UUID settlementId, String idempotencyKey, Instant updatedAt) {
      return delegate.markReleaseFailed(settlementId, idempotencyKey, updatedAt);
    }

    @Override
    public void updateHeld(UUID settlementId, String reason, Instant updatedAt) {
      delegate.updateHeld(settlementId, reason, updatedAt);
    }

    @Override
    public void updatePaid(
        UUID settlementId, String utrNumber, String receiptUrl, Instant paidAt, Instant updatedAt) {
      delegate.updatePaid(settlementId, utrNumber, receiptUrl, paidAt, updatedAt);
    }

    @Override
    public ListResult list(UUID pharmacyId, ListFilter filter) {
      return delegate.list(pharmacyId, filter);
    }

    @Override
    public boolean existsForPeriod(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return delegate.existsForPeriod(pharmacyId, periodStart, periodEnd);
    }

    @Override
    public long sumUnconsumedCarryForwardPaise(UUID pharmacyId) {
      return delegate.sumUnconsumedCarryForwardPaise(pharmacyId);
    }

    @Override
    public void markCarryForwardConsumed(UUID pharmacyId, Instant consumedAt) {
      delegate.markCarryForwardConsumed(pharmacyId, consumedAt);
    }
  }

  static final class ReplayAfterClaimStore implements SettlementStore {
    private final FakeSettlementStore delegate;
    private final AtomicBoolean firstLookup = new AtomicBoolean(true);

    ReplayAfterClaimStore(FakeSettlementStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<SettlementRow> findById(UUID settlementId) {
      return delegate.findById(settlementId);
    }

    @Override
    public Optional<SettlementRow> findByIdForPharmacy(UUID pharmacyId, UUID settlementId) {
      return delegate.findByIdForPharmacy(pharmacyId, settlementId);
    }

    @Override
    public Optional<SettlementRow> findByIdempotencyKey(String idempotencyKey) {
      if (firstLookup.compareAndSet(true, false) && idempotencyKey.startsWith("late-")) {
        return Optional.empty();
      }
      return delegate.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public Optional<SettlementRow> findByRazorpayxPayoutId(String razorpayxPayoutId) {
      return delegate.findByRazorpayxPayoutId(razorpayxPayoutId);
    }

    @Override
    public Optional<SettlementRow> findForPeriod(
        UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return delegate.findForPeriod(pharmacyId, periodStart, periodEnd);
    }

    @Override
    public Optional<SettlementRow> findLatestPaid(UUID pharmacyId) {
      return delegate.findLatestPaid(pharmacyId);
    }

    @Override
    public void insert(SettlementRow row) {
      delegate.insert(row);
    }

    @Override
    public void updateReleased(
        UUID settlementId,
        String status,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      delegate.updateReleased(
          settlementId,
          status,
          releasedBy,
          releasedAt,
          razorpayxPayoutId,
          idempotencyKey,
          updatedAt);
    }

    @Override
    public boolean claimForRelease(
        UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant updatedAt) {
      return false;
    }

    @Override
    public boolean finalizeRelease(
        UUID settlementId,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      return delegate.finalizeRelease(
          settlementId, releasedBy, releasedAt, razorpayxPayoutId, idempotencyKey, updatedAt);
    }

    @Override
    public boolean markReleaseFailed(UUID settlementId, String idempotencyKey, Instant updatedAt) {
      return delegate.markReleaseFailed(settlementId, idempotencyKey, updatedAt);
    }

    @Override
    public void updateHeld(UUID settlementId, String reason, Instant updatedAt) {
      delegate.updateHeld(settlementId, reason, updatedAt);
    }

    @Override
    public void updatePaid(
        UUID settlementId, String utrNumber, String receiptUrl, Instant paidAt, Instant updatedAt) {
      delegate.updatePaid(settlementId, utrNumber, receiptUrl, paidAt, updatedAt);
    }

    @Override
    public ListResult list(UUID pharmacyId, ListFilter filter) {
      return delegate.list(pharmacyId, filter);
    }

    @Override
    public boolean existsForPeriod(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return delegate.existsForPeriod(pharmacyId, periodStart, periodEnd);
    }

    @Override
    public long sumUnconsumedCarryForwardPaise(UUID pharmacyId) {
      return delegate.sumUnconsumedCarryForwardPaise(pharmacyId);
    }

    @Override
    public void markCarryForwardConsumed(UUID pharmacyId, Instant consumedAt) {
      delegate.markCarryForwardConsumed(pharmacyId, consumedAt);
    }
  }

  static final class ClaimFinalizeSettlementStore implements SettlementStore {
    private final FakeSettlementStore delegate;

    ClaimFinalizeSettlementStore(FakeSettlementStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<SettlementRow> findById(UUID settlementId) {
      return delegate.findById(settlementId);
    }

    @Override
    public Optional<SettlementRow> findByIdForPharmacy(UUID pharmacyId, UUID settlementId) {
      return delegate.findByIdForPharmacy(pharmacyId, settlementId);
    }

    @Override
    public Optional<SettlementRow> findByIdempotencyKey(String idempotencyKey) {
      return delegate.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public Optional<SettlementRow> findByRazorpayxPayoutId(String razorpayxPayoutId) {
      return delegate.findByRazorpayxPayoutId(razorpayxPayoutId);
    }

    @Override
    public Optional<SettlementRow> findForPeriod(
        UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return delegate.findForPeriod(pharmacyId, periodStart, periodEnd);
    }

    @Override
    public Optional<SettlementRow> findLatestPaid(UUID pharmacyId) {
      return delegate.findLatestPaid(pharmacyId);
    }

    @Override
    public void insert(SettlementRow row) {
      delegate.insert(row);
    }

    @Override
    public void updateReleased(
        UUID settlementId,
        String status,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      delegate.updateReleased(
          settlementId,
          status,
          releasedBy,
          releasedAt,
          razorpayxPayoutId,
          idempotencyKey,
          updatedAt);
    }

    @Override
    public boolean claimForRelease(
        UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant updatedAt) {
      return delegate.claimForRelease(settlementId, pharmacyId, idempotencyKey, updatedAt);
    }

    @Override
    public boolean finalizeRelease(
        UUID settlementId,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String idempotencyKey,
        Instant updatedAt) {
      return false;
    }

    @Override
    public boolean markReleaseFailed(UUID settlementId, String idempotencyKey, Instant updatedAt) {
      return delegate.markReleaseFailed(settlementId, idempotencyKey, updatedAt);
    }

    @Override
    public void updateHeld(UUID settlementId, String reason, Instant updatedAt) {
      delegate.updateHeld(settlementId, reason, updatedAt);
    }

    @Override
    public void updatePaid(
        UUID settlementId, String utrNumber, String receiptUrl, Instant paidAt, Instant updatedAt) {
      delegate.updatePaid(settlementId, utrNumber, receiptUrl, paidAt, updatedAt);
    }

    @Override
    public ListResult list(UUID pharmacyId, ListFilter filter) {
      return delegate.list(pharmacyId, filter);
    }

    @Override
    public boolean existsForPeriod(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return delegate.existsForPeriod(pharmacyId, periodStart, periodEnd);
    }

    @Override
    public long sumUnconsumedCarryForwardPaise(UUID pharmacyId) {
      return delegate.sumUnconsumedCarryForwardPaise(pharmacyId);
    }

    @Override
    public void markCarryForwardConsumed(UUID pharmacyId, Instant consumedAt) {
      delegate.markCarryForwardConsumed(pharmacyId, consumedAt);
    }
  }

  static final class FakeAudit implements AuditLogStore {
    final List<AuditLogRecord> entries = new ArrayList<>();

    @Override
    public void append(AuditLogRecord record) {
      entries.add(record);
    }
  }
}
