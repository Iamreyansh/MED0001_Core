package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort.PayoutResult;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.BankSnapshot;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.ListResult;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.SettlementRecord;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.Totals;
import com.nammamedmate.payment.application.port.out.SettlementNotificationPort;
import com.nammamedmate.payment.application.port.out.TcsRegisterWriterPort;
import com.nammamedmate.payment.domain.SettlementStatuses;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
class SettlementFacadeServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private PharmacySettlementPort settlements;
  @Mock private CashfreePayoutPort cashfree_payouts;
  @Mock private FinancialLedgerWriterPort ledger;
  @Mock private SettlementNotificationPort notifications;
  @Mock private TcsRegisterWriterPort tcsRegister;

  private SettlementFacadeService service;
  private final UUID pharmacyId = UUID.randomUUID();
  private final UUID settlementId = UUID.randomUUID();
  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new SettlementFacadeService(
            settlements, cashfree_payouts, ledger, notifications, tcsRegister, CLOCK);
  }

  @Test
  void ac002_netPayableFormula_52000gmv() {
    // GMV 5200000 paise, 8% commission → 416000, TCS 1% → 52000, net 4732000
    long gmv = 5_200_000L;
    long commission =
        BigDecimal.valueOf(gmv)
            .multiply(new BigDecimal("8"))
            .divide(new BigDecimal("100"), 0, java.math.RoundingMode.HALF_UP)
            .longValue();
    long tcs =
        BigDecimal.valueOf(gmv)
            .multiply(new BigDecimal("1"))
            .divide(new BigDecimal("100"), 0, java.math.RoundingMode.HALF_UP)
            .longValue();
    long net = gmv - commission - tcs;
    assertThat(commission).isEqualTo(416_000L);
    assertThat(tcs).isEqualTo(52_000L);
    assertThat(net).isEqualTo(4_732_000L);
    SettlementRecord row = pending(net, commission, tcs, gmv);
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(
            Optional.of(new BankSnapshot("XXXXXXXXXXXX4521", "HDFC", "HDFC0001234", "VERIFIED")));
    when(settlements.lineItems(any(), any(), any(), any())).thenReturn(List.of());
    Map<String, Object> detail = service.getAdminDetail(finance, settlementId);
    assertThat(detail.get("commission_earned")).isEqualTo(new BigDecimal("4160.00"));
    assertThat(detail.get("tcs_deducted")).isEqualTo(new BigDecimal("520.00"));
    assertThat(detail.get("net_payable")).isEqualTo(new BigDecimal("47320.00"));
  }

  @Test
  void ac003_belowThreshold_marksCarriedAnd422() {
    SettlementRecord row = pending(5_000L, 100L, 0L, 6_000L);
    when(settlements.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.release(finance, settlementId, "n", "k"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("AMOUNT_BELOW_THRESHOLD");
    verify(settlements).markBelowThreshold(eq(settlementId), anyString(), eq(NOW));
    verify(cashfree_payouts, never()).initiatePayout(any());
  }

  @Test
  void ac004_heldSettlement_rejectsRelease() {
    SettlementRecord row = record("HELD", 100_000L, 8_000L, 1_000L, 120_000L);
    when(settlements.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_HELD");
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k"))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(422);
  }

  @Test
  void ac005_ac008_release_triggersPayoutLedgerAndNotify() {
    SettlementRecord pending = pending(100_000L, 8_000L, 1_000L, 120_000L);
    SettlementRecord released =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "Apollo",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            120_000L,
            new BigDecimal("8.00"),
            8_000L,
            1_000L,
            1_440L,
            100_000L,
            2,
            "RELEASED",
            null,
            null,
            null,
            finance.subject(),
            NOW,
            "pout_abc",
            "notes",
            "idem-1");
    when(settlements.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId))
        .thenReturn(Optional.of(pending), Optional.of(released));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(
            Optional.of(new BankSnapshot("XXXXXXXXXXXX4521", "HDFC", "HDFC0001", "VERIFIED")));
    when(settlements.claimForRelease(settlementId, pharmacyId, "idem-1", NOW)).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("pout_abc", 4));
    when(settlements.finalizeRelease(
            eq(settlementId),
            eq(finance.subject()),
            eq(NOW),
            eq("pout_abc"),
            any(),
            eq("idem-1"),
            eq(NOW)))
        .thenReturn(true);

    Map<String, Object> result = service.release(finance, settlementId, "ok", "idem-1");
    assertThat(result.get("status")).isEqualTo("RELEASED");
    assertThat(result.get("cashfree_transfer_id")).isEqualTo("pout_abc");
    assertThat(result.get("notification_sent")).isEqualTo(true);

    ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
    verify(ledger)
        .append(
            type.capture(),
            eq(settlementId),
            eq("SETTLEMENT"),
            eq(0L),
            eq(100_000L),
            anyString(),
            any());
    assertThat(type.getValue()).isEqualTo("PAYOUT_PHARMACY");
    verify(ledger)
        .append(
            eq("TCS_COLLECTED"),
            eq(settlementId),
            eq("SETTLEMENT"),
            eq(1_000L),
            eq(0L),
            anyString(),
            any());
    verify(notifications).settlementReleased(pharmacyId, settlementId, 100_000L);
  }

  @Test
  void ac006_bulkRelease_skipsHeldAndOverThreshold() {
    SettlementRecord ok = pending(UUID.randomUUID(), 50_000L, 4_000L, 500L, 60_000L);
    when(settlements.listPendingForBulk(SettlementStatuses.DEFAULT_BULK_MAX_PAISE, 200))
        .thenReturn(List.of(ok));
    when(settlements.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(settlements.findById(ok.id())).thenReturn(Optional.of(ok), Optional.of(releasedOf(ok)));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(
            Optional.of(new BankSnapshot("XXXXXXXXXXXX1111", "SBI", "SBIN0001", "VERIFIED")));
    when(settlements.claimForRelease(eq(ok.id()), eq(pharmacyId), anyString(), eq(NOW)))
        .thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("pout_b", 4));
    when(settlements.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);

    Map<String, Object> result =
        service.releaseAll(finance, new BigDecimal("50000.00"), "bulk", "bulk-key");
    assertThat(result.get("attempted")).isEqualTo(1);
    assertThat(result.get("released")).isEqualTo(1);
    assertThat(result.get("failed")).isEqualTo(0);
  }

  @Test
  void ac006_bulkRelease_recordsFailureReasons() {
    SettlementRecord noBank = pending(UUID.randomUUID(), 20_000L, 1_000L, 0L, 25_000L);
    when(settlements.listPendingForBulk(anyLong(), eq(200))).thenReturn(List.of(noBank));
    when(settlements.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(settlements.findById(noBank.id())).thenReturn(Optional.of(noBank));
    when(settlements.findVerifiedBank(pharmacyId)).thenReturn(Optional.empty());

    Map<String, Object> result = service.releaseAll(finance, null, null, "bulk-2");
    assertThat(result.get("released")).isEqualTo(0);
    assertThat(result.get("failed")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get("failures");
    assertThat(failures.getFirst().get("reason")).isEqualTo("PHARMACY_NO_BANK_ACCOUNT");
  }

  @Test
  void ac007_pharmacyDetail_otherPharmacyForbidden() {
    UUID other = UUID.randomUUID();
    SettlementRecord row =
        new SettlementRecord(
            settlementId,
            other,
            "Other",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            1000L,
            new BigDecimal("8"),
            80L,
            10L,
            14L,
            910L,
            1,
            "PENDING_RELEASE",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row));
    assertThatThrownBy(() -> service.getPharmacyDetail(owner, settlementId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.getPharmacyDetail(owner, settlementId))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
  }

  @Test
  void listAdmin_normalisesPendingStatusAndKpis() {
    SettlementRecord row = pending(100_000L, 8_000L, 1_000L, 120_000L);
    when(settlements.list(any())).thenReturn(new ListResult(List.of(row), 1));
    when(settlements.totals(any())).thenReturn(new Totals(120_000L, 8_000L, 1_000L, 100_000L));
    when(settlements.kpis(any(), any())).thenReturn(new KpiSnapshot(1, 2, 3, 4));

    var page = service.listAdmin(finance, "PENDING", null, null, 1, 20);
    assertThat(page.data().get("settlements")).asList().hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> item =
        ((List<Map<String, Object>>) page.data().get("settlements")).getFirst();
    assertThat(item.get("status")).isEqualTo("PENDING");
  }

  @Test
  void hold_setsHeldAndNotifies() {
    when(settlements.findById(settlementId))
        .thenReturn(Optional.of(pending(100_000L, 1, 0, 100_000L)));
    Map<String, Object> result = service.hold(finance, settlementId, "Compliance", "case-1");
    assertThat(result.get("status")).isEqualTo("HELD");
    verify(settlements)
        .markHeld(eq(settlementId), eq(finance.subject()), eq("Compliance"), eq("case-1"), eq(NOW));
    verify(notifications).settlementHeld(pharmacyId, settlementId, "Compliance");
  }

  @Test
  void unhold_clearsHoldForRelease() {
    when(settlements.findById(settlementId))
        .thenReturn(Optional.of(record("HELD", 100_000L, 1, 0, 100_000L)))
        .thenReturn(Optional.of(pending(100_000L, 1, 0, 100_000L)));
    Map<String, Object> result = service.unhold(finance, settlementId, "cleared");
    assertThat(result.get("status")).isEqualTo("PENDING");
    verify(settlements).markUnheld(eq(settlementId), eq(finance.subject()), eq("cleared"), eq(NOW));
    assertThatThrownBy(() -> service.unhold(finance, settlementId, "again"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SETTLEMENT_NOT_HELD");
  }

  @Test
  void alreadyReleased_409() {
    when(settlements.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId))
        .thenReturn(Optional.of(record("RELEASED", 100_000L, 1, 0, 100_000L)));
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_RELEASED");
  }

  @Test
  void pharmacyList_scopedToOwner() {
    when(settlements.list(any())).thenReturn(new ListResult(List.of(pending(1, 0, 0, 1)), 1));
    var page = service.listPharmacy(owner, "RELEASED", 1, 10);
    assertThat(page.meta().total()).isEqualTo(1);
  }

  @Test
  void idempotentReplay_returnsPriorRelease() {
    SettlementRecord released = releasedOf(pending(100_000L, 1, 0, 100_000L));
    when(settlements.findByIdempotencyKey("idem")).thenReturn(Optional.of(released));
    Map<String, Object> result = service.release(finance, settlementId, null, "idem");
    assertThat(result.get("cashfree_transfer_id")).isEqualTo("pout_x");
    verify(cashfree_payouts, never()).initiatePayout(any());
  }

  @Test
  void payoutFailure_mapsToCashfreePayoutFailed() {
    SettlementRecord row = pending(100_000L, 1, 0, 100_000L);
    when(settlements.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(
            Optional.of(new BankSnapshot("XXXXXXXXXXXX9999", "ICICI", "ICIC0001", "VERIFIED")));
    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any()))
        .thenThrow(new AppException("CASHFREE_PAYOUT_FAILED", "down", 502));

    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
    verify(settlements).markReleaseFailed(settlementId, "k", NOW);
  }

  @Test
  void requireIdempotencyKey_blankRejected() {
    assertThatThrownBy(() -> SettlementFacadeService.requireIdempotencyKey(" "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  private SettlementRecord pending(long net, long commission, long tcs, long gmv) {
    return pending(settlementId, net, commission, tcs, gmv);
  }

  private SettlementRecord pending(UUID id, long net, long commission, long tcs, long gmv) {
    return new SettlementRecord(
        id,
        pharmacyId,
        "Apollo Pharmacy, Koramangala",
        LocalDate.of(2026, 7, 14),
        LocalDate.of(2026, 7, 20),
        gmv,
        new BigDecimal("8.00"),
        commission,
        tcs,
        BigDecimal.valueOf(commission)
            .multiply(new BigDecimal("0.18"))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValue(),
        net,
        10,
        "PENDING_RELEASE",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private SettlementRecord record(String status, long net, long commission, long tcs, long gmv) {
    SettlementRecord base = pending(net, commission, tcs, gmv);
    return new SettlementRecord(
        base.id(),
        base.pharmacyId(),
        base.pharmacyName(),
        base.cycleFrom(),
        base.cycleTo(),
        base.gmvPaise(),
        base.commissionPct(),
        base.commissionEarnedPaise(),
        base.tcsDeductedPaise(),
        base.gstOnCommissionPaise(),
        base.netPayablePaise(),
        base.ordersCount(),
        status,
        "held",
        finance.subject(),
        NOW,
        base.releasedBy(),
        base.releasedAt(),
        base.cashfreeTransferId(),
        base.notes(),
        base.releaseIdempotencyKey());
  }

  private SettlementRecord releasedOf(SettlementRecord pending) {
    return new SettlementRecord(
        pending.id(),
        pending.pharmacyId(),
        pending.pharmacyName(),
        pending.cycleFrom(),
        pending.cycleTo(),
        pending.gmvPaise(),
        pending.commissionPct(),
        pending.commissionEarnedPaise(),
        pending.tcsDeductedPaise(),
        pending.gstOnCommissionPaise(),
        pending.netPayablePaise(),
        pending.ordersCount(),
        "RELEASED",
        null,
        null,
        null,
        finance.subject(),
        NOW,
        "pout_x",
        null,
        "idem");
  }
}
