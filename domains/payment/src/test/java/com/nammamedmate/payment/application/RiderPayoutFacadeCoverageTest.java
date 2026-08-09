package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort.PayoutResult;
import com.nammamedmate.payment.application.port.out.RiderPayoutNotificationPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.EarningsEntry;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.PaymentInstrument;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.PayoutRecord;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.RiderSnapshot;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiderPayoutFacadeCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock RiderPayoutPort payouts;
  @Mock RazorpayXPayoutPort razorpayx;
  @Mock FinancialLedgerWriterPort ledger;
  @Mock RiderPayoutNotificationPort notifications;

  RiderPayoutFacadeService service;
  UUID riderId = UUID.randomUUID();
  UUID payoutId = UUID.randomUUID();
  MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new RiderPayoutFacadeService(
            payouts, razorpayx, ledger, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void roleAndValidationBranches() {
    assertThatThrownBy(() -> RiderPayoutFacadeService.requireFinanceRole(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> RiderPayoutFacadeService.requireFinanceRole(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> RiderPayoutFacadeService.requireIdempotencyKey(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> RiderPayoutFacadeService.requireIdempotencyKey("x".repeat(129)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> RiderPayoutFacadeService.requireIdempotencyKey(" "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    RiderPayoutFacadeService.requireFinanceRole(superAdmin);
    assertThat(new RiderPayoutFacadeService.PagedResult(null, null).data()).isEmpty();
  }

  @Test
  void ledger_withCycleSummaryAndEmptyPayout() {
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "Ravi", 0)));
    when(payouts.countEarnings(riderId, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 20)))
        .thenReturn(1L);
    when(payouts.listEarnings(eq(riderId), any(), any(), eq(50), eq(0)))
        .thenReturn(
            List.of(
                new EarningsEntry(
                    LocalDate.of(2026, 7, 14),
                    UUID.randomUUID(),
                    "MED-1",
                    2000,
                    1000,
                    500,
                    3500,
                    true,
                    new BigDecimal("2.4"),
                    NOW)));
    when(payouts.findByRiderAndCycle(riderId, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 20)))
        .thenReturn(Optional.of(row("PENDING", 200_000L)));

    Map<String, Object> data =
        service
            .ledger(finance, riderId, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 20), 1, 50)
            .data();
    assertThat(data.get("rider_name")).isEqualTo("Ravi");
    assertThat(data.get("entries")).asList().hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) data.get("cycle_summary");
    assertThat(summary.get("net_payout")).isEqualTo(new BigDecimal("2000.00"));

    Map<String, Object> emptySummary =
        service.ledger(finance, riderId, null, null, null, null).data();
    @SuppressWarnings("unchecked")
    Map<String, Object> zeros = (Map<String, Object>) emptySummary.get("cycle_summary");
    assertThat(zeros.get("net_payout")).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void release_missingPayoutIdAndWrongRiderAndNoInstrument() {
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.release(finance, riderId, null, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");

    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    UUID other = UUID.randomUUID();
    assertThatThrownBy(() -> service.release(finance, other, payoutId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");

    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NO_PAYMENT_DETAILS");
  }

  @Test
  void release_claimConflictAndIdempotencyMismatch() {
    PayoutRecord other =
        new PayoutRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "X",
            null,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            0,
            0,
            0,
            0,
            0,
            0,
            100_000L,
            "RELEASED",
            null,
            "p",
            null,
            null,
            finance.subject(),
            NOW,
            0,
            null,
            "idem");
    when(payouts.findByIdempotencyKey("idem")).thenReturn(Optional.of(other));
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "idem"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

    PayoutRecord samePayoutWrongRider =
        new PayoutRecord(
            payoutId,
            UUID.randomUUID(),
            "X",
            null,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            0,
            0,
            0,
            0,
            0,
            0,
            100_000L,
            "RELEASED",
            null,
            "p",
            null,
            null,
            finance.subject(),
            NOW,
            0,
            null,
            "idem2");
    when(payouts.findByIdempotencyKey("idem2")).thenReturn(Optional.of(samePayoutWrongRider));
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "idem2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_CONFLICT");
  }

  @Test
  void release_finalizeFailsAndRuntimeMapped() {
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(razorpayx.initiatePayout(any())).thenReturn(new PayoutResult("pout", 4));
    when(payouts.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(false);

    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, "n", "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_CONFLICT");
    verify(payouts).markFailed(eq(payoutId), eq("k"), anyString(), eq(NOW));
    verify(payouts, never()).scheduleRetry(any(), any(), anyString(), any(), any());
    verify(notifications).payoutFailed(eq(riderId), eq(payoutId), anyString());

    when(razorpayx.initiatePayout(any())).thenThrow(new RuntimeException("boom"));
    when(payouts.findByIdempotencyKey("k2")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");
    verify(payouts).scheduleRetry(eq(payoutId), eq("k2"), anyString(), any(), eq(NOW));
  }

  @Test
  void belowStatusAndHistoryUnauthorized() {
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(payouts.findById(payoutId))
        .thenReturn(Optional.of(row("BELOW_THRESHOLD_CARRIED_FORWARD", 50)));
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_BELOW_THRESHOLD");

    assertThatThrownBy(() -> service.history(null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void claimReplayReturnsPrior() {
    PayoutRecord released = row("RELEASED", 100_000L);
    when(payouts.findByIdempotencyKey("k")).thenReturn(Optional.empty(), Optional.of(released));
    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(false);

    Map<String, Object> result = service.release(finance, riderId, payoutId, null, "k");
    assertThat(result.get("status")).isEqualTo("RELEASED");
  }

  @Test
  void coverageGaps_ledgerNullsAndNotFound() {
    when(payouts.findRider(riderId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.ledger(finance, riderId, null, null, 0, 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.countEarnings(any(), any(), any())).thenReturn(1L);
    when(payouts.listEarnings(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(new EarningsEntry(null, null, "MED", 100, 0, 0, 100, false, null, null)));
    Map<String, Object> data = service.ledger(finance, riderId, null, null, 0, 200).data();
    assertThat(service.ledger(finance, riderId, null, null, 1, 0).meta().limit()).isEqualTo(50);
    assertThat(service.ledger(finance, riderId, null, null, 1, 50).meta().limit()).isEqualTo(50);
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = ((List<Map<String, Object>>) data.get("entries")).getFirst();
    assertThat(entry.get("date")).isNull();
    assertThat(entry.get("order_id")).isNull();
    assertThat(entry.get("distance_km")).isEqualTo(0.0);
    assertThat(entry.get("completed_at")).isNull();
  }

  @Test
  void coverageGaps_releaseEdgeBranches() {
    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");

    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    when(payouts.findRider(riderId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 50L)));
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_BELOW_THRESHOLD");
    verify(payouts).markBelowThreshold(eq(payoutId), eq("Below Rs 100 threshold"), eq(NOW));

    when(payouts.findById(payoutId))
        .thenReturn(Optional.of(row("PENDING", 100_000L)), Optional.empty());
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "k2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");
  }

  @Test
  void coverageGaps_successMissingReloadAndBlankMessage() {
    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(payoutId))
        .thenReturn(Optional.of(row("PENDING", 100_000L)), Optional.empty());
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(razorpayx.initiatePayout(any())).thenReturn(new PayoutResult("pout", 4));
    when(payouts.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, "n", "ok"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");

    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    org.mockito.Mockito.doThrow(new AppException("RAZORPAY_ERROR", " ", 502))
        .when(razorpayx)
        .initiatePayout(any());
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "err"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");

    org.mockito.Mockito.doThrow(new RuntimeException()).when(razorpayx).initiatePayout(any());
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, null, "rt"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RAZORPAY_PAYOUT_FAILED");
  }

  @Test
  void coverageGaps_listHistoryDefaultsAndNullNames() {
    when(payouts.list(any())).thenReturn(new RiderPayoutPort.ListResult(null, 0));
    when(payouts.summary(any(), any()))
        .thenReturn(new RiderPayoutPort.SummarySnapshot(0, 0, 0, 0, 0, 0));
    assertThat(service.listAdmin(finance, null, " ", null, 0, 0).data().get("payouts"))
        .asList()
        .isEmpty();
    assertThat(service.listAdmin(finance, null, null, null, null, null).meta().limit())
        .isEqualTo(20);
    assertThat(service.listAdmin(finance, null, null, null, 1, 20).meta().page()).isEqualTo(1);

    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.countEarnings(any(), any(), any())).thenReturn(0L);
    when(payouts.listEarnings(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    // from set, to null → empty summary zeros without store lookup
    assertThat(
            service
                .ledger(finance, riderId, LocalDate.of(2026, 7, 14), null, null, null)
                .data()
                .get("cycle_summary"))
        .isInstanceOf(Map.class);
    when(payouts.findByRiderAndCycle(
            eq(riderId), eq(LocalDate.of(2026, 7, 14)), eq(LocalDate.of(2026, 7, 20))))
        .thenReturn(Optional.empty());
    assertThat(
            ((Map<?, ?>)
                    service
                        .ledger(
                            finance,
                            riderId,
                            LocalDate.of(2026, 7, 14),
                            LocalDate.of(2026, 7, 20),
                            1,
                            50)
                        .data()
                        .get("cycle_summary"))
                .get("net_payout"))
        .isEqualTo(new java.math.BigDecimal("0.00"));

    PayoutRecord nameless =
        new PayoutRecord(
            UUID.randomUUID(),
            riderId,
            null,
            null,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            20_000L,
            0,
            0,
            0,
            0,
            0,
            20_000L,
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
    when(payouts.listPendingForBulk(anyLong(), anyLong(), any(), eq(200)))
        .thenReturn(List.of(nameless));
    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(nameless.id())).thenReturn(Optional.of(nameless));
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId)).thenReturn(Optional.empty());
    Map<String, Object> bulk = service.releaseAll(finance, null, null, null, "bulk");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fails = (List<Map<String, Object>>) bulk.get("failures");
    assertThat(fails.getFirst().get("rider_name")).isEqualTo("");

    MedmatePrincipal riderP =
        new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
    when(payouts.listForRider(eq(riderId), eq(20), eq(0)))
        .thenReturn(new RiderPayoutPort.ListResult(List.of(row("PENDING", 100_000L)), 1));
    when(payouts.listForRider(eq(riderId), eq(5), eq(5)))
        .thenReturn(
            new RiderPayoutPort.ListResult(
                List.of(
                    new PayoutRecord(
                        payoutId,
                        riderId,
                        "R",
                        null,
                        null,
                        LocalDate.of(2026, 7, 14),
                        LocalDate.of(2026, 7, 20),
                        100_000L,
                        0,
                        0,
                        0,
                        0,
                        0,
                        100_000L,
                        "RELEASED",
                        null,
                        "pout",
                        null,
                        null,
                        finance.subject(),
                        NOW,
                        0,
                        null,
                        null)),
                1));
    Map<String, Object> hist = service.history(riderP, null, null).data();
    @SuppressWarnings("unchecked")
    Map<String, Object> item = ((List<Map<String, Object>>) hist.get("payouts")).getFirst();
    assertThat(item.get("released_at")).isNull();
    assertThat(service.history(riderP, 0, 0).meta().limit()).isEqualTo(20);
    @SuppressWarnings("unchecked")
    Map<String, Object> releasedItem =
        ((List<Map<String, Object>>) service.history(riderP, 2, 5).data().get("payouts"))
            .getFirst();
    assertThat(releasedItem.get("released_at")).isEqualTo(NOW.toString());

    PayoutRecord releasedNulls =
        new PayoutRecord(
            payoutId,
            riderId,
            "R",
            null,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            0,
            0,
            0,
            0,
            0,
            100_000L,
            "RELEASED",
            null,
            "pout",
            null,
            null,
            null,
            null,
            0,
            null,
            "idem");
    when(payouts.findByIdempotencyKey("replay")).thenReturn(Optional.of(releasedNulls));
    Map<String, Object> replay = service.release(finance, riderId, payoutId, null, "replay");
    assertThat(replay.get("released_by")).isNull();
    assertThat(replay.get("released_at")).isNull();
  }

  @Test
  void release_finalizeConflictAfterProviderAccept() {
    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(razorpayx.initiatePayout(any())).thenReturn(new PayoutResult("pout", 4));
    when(payouts.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(false);
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, "n", "fin-fail"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_CONFLICT");
  }

  @Test
  void release_finalizeRuntimeExceptionAfterProviderAccept() {
    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(razorpayx.initiatePayout(any())).thenReturn(new PayoutResult("pout_rt", 4));
    when(payouts.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenThrow(new RuntimeException("db down"));
    assertThatThrownBy(() -> service.release(finance, riderId, payoutId, "n", "fin-rt"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_CONFLICT");
  }

  @Test
  void release_withTransactionManager_andNonRazorpayAppException() {
    org.springframework.transaction.PlatformTransactionManager tm =
        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
    org.springframework.transaction.TransactionStatus status =
        org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class);
    when(tm.getTransaction(any())).thenReturn(status);
    RiderPayoutFacadeService withTx =
        new RiderPayoutFacadeService(
            payouts, razorpayx, ledger, notifications, Clock.fixed(NOW, ZoneOffset.UTC), tm);

    when(payouts.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(payouts.findById(payoutId)).thenReturn(Optional.of(row("PENDING", 100_000L)));
    when(payouts.findRider(riderId)).thenReturn(Optional.of(new RiderSnapshot(riderId, "R", 0)));
    when(payouts.codFloatLimitPaise()).thenReturn(200_000L);
    when(payouts.findPaymentInstrument(riderId))
        .thenReturn(Optional.of(new PaymentInstrument("UPI", "x")));
    when(payouts.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    org.mockito.Mockito.doThrow(new AppException("VALIDATION_ERROR", "nope", 400))
        .when(razorpayx)
        .initiatePayout(any());
    assertThatThrownBy(() -> withTx.release(finance, riderId, payoutId, null, "tm-key"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    verify(tm, org.mockito.Mockito.atLeastOnce()).getTransaction(any());
  }

  private PayoutRecord row(String status, long net) {
    return new PayoutRecord(
        payoutId,
        riderId,
        "Ravi",
        null,
        null,
        LocalDate.of(2026, 7, 14),
        LocalDate.of(2026, 7, 20),
        net,
        0,
        0,
        0,
        0,
        0,
        net,
        status,
        null,
        "RELEASED".equals(status) ? "pout_x" : null,
        null,
        null,
        "RELEASED".equals(status) ? finance.subject() : null,
        "RELEASED".equals(status) ? NOW : null,
        0,
        null,
        null);
  }
}
