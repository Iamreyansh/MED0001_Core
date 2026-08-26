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
import com.nammamedmate.payment.application.port.out.RiderPayoutNotificationPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.ListResult;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.PaymentInstrument;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.PayoutRecord;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.RiderSnapshot;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.SummarySnapshot;
import com.nammamedmate.payment.domain.RiderPayoutStatuses;
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
class RiderPayoutFacadeServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private RiderPayoutPort payouts;
  @Mock private CashfreePayoutPort cashfree_payouts;
  @Mock private FinancialLedgerWriterPort ledger;
  @Mock private RiderPayoutNotificationPort notifications;

  private RiderPayoutFacadeService service;
  private final UUID riderId = UUID.randomUUID();
  private final UUID payoutId = UUID.randomUUID();
  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal rider =
      new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service = new RiderPayoutFacadeService(payouts, cashfree_payouts, ledger, notifications, CLOCK);
  }

  @Test
  void ac002_codUnresolved_blocksRelease() {
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(pending(200_000L)));
    when(payouts.findRider(riderId))
        .thenReturn(Optional.of(new RiderSnapshot(riderId, "Ravi", 250_000L)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);

    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COD_UNRESOLVED");
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(422);
    verify(cashfree_payouts, never()).initiatePayout(any());
  }

  @Test
  void ac003_belowThreshold_marksCarriedAnd422() {
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(pending(5_000L)));
    when(payouts.findRider(riderId))
        .thenReturn(Optional.of(new RiderSnapshot(riderId, "Ravi", 0L)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);

    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, "n", "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYOUT_BELOW_THRESHOLD");
    verify(payouts).markBelowThreshold(eq(payoutId), anyString(), eq(NOW));
    verify(cashfree_payouts, never()).initiatePayout(any());
  }

  @Test
  void ac004_ac008_release_triggersPayoutLedgerAndSms() {
    PayoutRecord pending = pending(200_000L);
    PayoutRecord released = releasedOf(pending);
    when(payouts.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(pending), Optional.of(released));
    when(payouts.findRider(riderId))
        .thenReturn(Optional.of(new RiderSnapshot(riderId, "Ravi", 0L)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "ravi@okaxis")));
    when(payouts.claimForRelease(payoutId, riderId, "idem-1", NOW)).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("pout_abc", 4));
    when(payouts.finalizeRelease(
            eq(payoutId),
            eq(finance.subject()),
            eq(NOW),
            eq("pout_abc"),
            any(),
            eq("idem-1"),
            eq(NOW)))
        .thenReturn(true);

    Map<String, Object> result = service.release(finance, riderId, payoutId, "ok", "idem-1");
    assertThat(result.get("status")).isEqualTo("RELEASED");
    assertThat(result.get("cashfree_transfer_id")).isEqualTo("pout_x");

    ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
    verify(ledger)
        .append(
            type.capture(),
            eq(payoutId),
            eq("RIDER_PAYOUT"),
            eq(0L),
            eq(200_000L),
            anyString(),
            any());
    assertThat(type.getValue()).isEqualTo("PAYOUT_RIDER");
    verify(notifications).payoutReleased(riderId, payoutId, 200_000L, "pout_abc");
    verify(payouts).adjustEarningsWallet(riderId, -200_000L, NOW);
  }

  @Test
  void ac005_cashfreeFailure_schedulesRetryThenFails() {
    PayoutRecord pending = pending(200_000L);
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(pending));
    when(payouts.findRider(riderId))
        .thenReturn(Optional.of(new RiderSnapshot(riderId, "Ravi", 0L)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any()))
        .thenThrow(new AppException("CASHFREE_PAYOUT_FAILED", "down", 502));

    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
    verify(payouts)
        .scheduleRetry(
            eq(payoutId),
            eq("k"),
            anyString(),
            eq(NOW.plus(RiderPayoutFacadeService.RETRY_AFTER)),
            eq(NOW));
    verify(notifications, never()).payoutFailed(any(), any(), any());

    PayoutRecord retried =
        new PayoutRecord(
            pending.id(),
            pending.riderId(),
            pending.riderName(),
            pending.zoneId(),
            pending.zoneName(),
            pending.cycleFrom(),
            pending.cycleTo(),
            pending.baseEarningsPaise(),
            pending.incentivesPaise(),
            pending.tipsPaise(),
            pending.streakBonusPaise(),
            pending.carryForwardPaise(),
            pending.codDeductedPaise(),
            pending.netPayoutPaise(),
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            null,
            null);
    when(payouts.findById(payoutId)).thenReturn(Optional.of(retried));
    when(payouts.findByIdempotencyKey("k2")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k2"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
    verify(payouts).markFailed(eq(payoutId), eq("k2"), anyString(), eq(NOW));
    verify(notifications).payoutFailed(eq(riderId), eq(payoutId), anyString());
  }

  @Test
  void ac006_bulkRelease_skipsHeldOverAndUnderThreshold() {
    PayoutRecord ok = pending(UUID.randomUUID(), 50_000L);
    when(payouts.listPendingForBulk(
            RiderPayoutStatuses.MIN_RELEASE_PAISE,
            RiderPayoutStatuses.DEFAULT_BULK_MAX_PAISE,
            null,
            200))
        .thenReturn(List.of(ok));
    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(ok.id())).thenReturn(Optional.of(ok), Optional.of(releasedOf(ok)));
    when(payouts.findRider(ok.riderId()))
        .thenReturn(Optional.of(new RiderSnapshot(ok.riderId(), "Ravi", 0L)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(ok.riderId()))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(eq(ok.id()), eq(ok.riderId()), anyString(), eq(NOW)))
        .thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("pout_b", 4));
    when(payouts.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);

    Map<String, Object> result = service.releaseAll(finance, null, null, "bulk", "bulk-key");
    assertThat(result.get("attempted")).isEqualTo(1);
    assertThat(result.get("released")).isEqualTo(1);
    assertThat(result.get("failed")).isEqualTo(0);
  }

  @Test
  void ac006_bulkRelease_recordsFailureReasons() {
    PayoutRecord noPay = pending(UUID.randomUUID(), 20_000L);
    when(payouts.listPendingForBulk(anyLong(), anyLong(), any(), eq(200)))
        .thenReturn(List.of(noPay));
    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(noPay.id())).thenReturn(Optional.of(noPay));
    when(payouts.findRider(noPay.riderId()))
        .thenReturn(Optional.of(new RiderSnapshot(noPay.riderId(), "Suresh", 0L)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(noPay.riderId())).thenReturn(Optional.empty());

    Map<String, Object> result =
        service.releaseAll(
            finance, new BigDecimal("10000.00"), LocalDate.of(2026, 7, 14), null, "b2");
    assertThat(result.get("released")).isEqualTo(0);
    assertThat(result.get("failed")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get("failures");
    assertThat(failures.getFirst().get("reason")).isEqualTo("RIDER_NO_PAYMENT_DETAILS");
  }

  @Test
  void ac007_history_scopedToAuthenticatedRider() {
    when(payouts.listForRider(eq(riderId), eq(20), eq(0)))
        .thenReturn(new ListResult(List.of(pending(100_000L)), 1));
    var page = service.history(rider, 1, 20);
    assertThat(page.meta().total()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.data().get("payouts");
    assertThat(items).hasSize(1);
    assertThatThrownBy(() -> service.history(finance, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void listAdmin_summaryAndStatusFilter() {
    when(payouts.list(any())).thenReturn(new ListResult(List.of(pending(100_000L)), 1));
    when(payouts.summary(any(), any())).thenReturn(new SummarySnapshot(1, 100_000L, 0, 0, 0, 0));
    var page = service.listAdmin(finance, LocalDate.of(2026, 7, 14), "PENDING", null, 1, 20);
    assertThat(page.data().get("payouts")).asList().hasSize(1);
    assertThatThrownBy(() -> service.listAdmin(finance, null, "NOPE", null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
  }

  @Test
  void alreadyReleased_409() {
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(releasedOf(pending(100_000L))));
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYOUT_ALREADY_RELEASED");
  }

  @Test
  void idempotentReplay_returnsPriorRelease() {
    PayoutRecord released = releasedOf(pending(100_000L));
    when(payouts.findByIdempotencyKey("idem")).thenReturn(Optional.of(released));
    Map<String, Object> result = service.release(finance, riderId, payoutId, null, "idem");
    assertThat(result.get("cashfree_transfer_id")).isEqualTo("pout_x");
    verify(cashfree_payouts, never()).initiatePayout(any());
  }

  private PayoutRecord pending(long net) {
    return pending(payoutId, net);
  }

  private PayoutRecord pending(UUID id, long net) {
    return new PayoutRecord(
        id,
        id.equals(payoutId) ? riderId : UUID.randomUUID(),
        "Ravi Kumar",
        UUID.randomUUID(),
        "Koramangala",
        LocalDate.of(2026, 7, 14),
        LocalDate.of(2026, 7, 20),
        net,
        0,
        0,
        0,
        0,
        0,
        net,
        "PENDING",
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        null,
        null);
  }

  private PayoutRecord releasedOf(PayoutRecord pending) {
    return new PayoutRecord(
        pending.id(),
        pending.riderId(),
        pending.riderName(),
        pending.zoneId(),
        pending.zoneName(),
        pending.cycleFrom(),
        pending.cycleTo(),
        pending.baseEarningsPaise(),
        pending.incentivesPaise(),
        pending.tipsPaise(),
        pending.streakBonusPaise(),
        pending.carryForwardPaise(),
        pending.codDeductedPaise(),
        pending.netPayoutPaise(),
        "RELEASED",
        null,
        "pout_x",
        null,
        null,
        finance.subject(),
        NOW,
        0,
        null,
        "idem");
  }
}
