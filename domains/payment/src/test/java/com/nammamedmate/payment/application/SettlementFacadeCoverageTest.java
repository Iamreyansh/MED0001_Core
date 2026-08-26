package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort.PayoutResult;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.BankSnapshot;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.LineItem;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.ListResult;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.SettlementRecord;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.Totals;
import com.nammamedmate.payment.application.port.out.SettlementNotificationPort;
import com.nammamedmate.payment.application.port.out.TcsRegisterWriterPort;
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
class SettlementFacadeCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock PharmacySettlementPort settlements;
  @Mock CashfreePayoutPort cashfree_payouts;
  @Mock FinancialLedgerWriterPort ledger;
  @Mock SettlementNotificationPort notifications;
  @Mock TcsRegisterWriterPort tcsRegister;

  SettlementFacadeService service;
  UUID pharmacyId = UUID.randomUUID();
  UUID settlementId = UUID.randomUUID();
  MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new SettlementFacadeService(
            settlements,
            cashfree_payouts,
            ledger,
            notifications,
            tcsRegister,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void roleAndValidationBranches() {
    assertThatThrownBy(() -> SettlementFacadeService.requireFinanceRole(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> SettlementFacadeService.requireFinanceRole(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> SettlementFacadeService.requireIdempotencyKey("x".repeat(129)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listAdmin(customer, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listPharmacy(customer, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listPharmacy(null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.hold(finance, settlementId, " ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listAdmin(finance, "NOPE", null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_STATUS");
  }

  @Test
  void detailWithLineItemsAndBankNull() {
    SettlementRecord row =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            10_000L,
            new BigDecimal("8"),
            800L,
            0L,
            0L,
            9_200L,
            0,
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
    when(settlements.findVerifiedBank(pharmacyId)).thenReturn(Optional.empty());
    when(settlements.lineItems(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                new LineItem(
                    UUID.randomUUID(),
                    "MED-1",
                    NOW,
                    10_000L,
                    new BigDecimal("8"),
                    800L,
                    100L,
                    9_100L)));
    var detail = service.getAdminDetail(finance, settlementId);
    assertThat(detail.get("pharmacy_bank")).isNull();
    assertThat(detail.get("orders_count")).isEqualTo(1);
    assertThat(detail.get("line_items")).asList().hasSize(1);

    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    assertThat(service.getPharmacyDetail(owner, settlementId).get("status")).isEqualTo("PENDING");
  }

  @Test
  void claimConflictAndFinalizeFailureAndRuntimePayout() {
    SettlementRecord row =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            new BigDecimal("8"),
            8000L,
            0L,
            0L,
            92_000L,
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
    when(settlements.findByIdempotencyKey("k")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot("XX12", "B", "IFSC", "VERIFIED")));
    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SETTLEMENT_CONFLICT");

    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("p", 4));
    when(settlements.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(false);
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SETTLEMENT_CONFLICT");

    when(settlements.findByIdempotencyKey("k3")).thenReturn(Optional.empty());
    when(cashfree_payouts.initiatePayout(any())).thenThrow(new RuntimeException("boom"));
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
  }

  @Test
  void idempotencyConflictAndHoldAlreadyReleased() {
    SettlementRecord other =
        new SettlementRecord(
            UUID.randomUUID(),
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            1,
            BigDecimal.ONE,
            0,
            0,
            0,
            1,
            0,
            "RELEASED",
            null,
            null,
            null,
            finance.subject(),
            NOW,
            "p",
            null,
            "idem");
    when(settlements.findByIdempotencyKey("idem")).thenReturn(Optional.of(other));
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "idem"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

    when(settlements.findById(settlementId)).thenReturn(Optional.of(other));
    assertThatThrownBy(() -> service.hold(finance, settlementId, "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_RELEASED");
  }

  @Test
  void claimReplayAfterFailedClaim() {
    SettlementRecord pending =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            null,
            8000L,
            0L,
            1440L,
            92_000L,
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
    SettlementRecord released =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            new BigDecimal("8"),
            8000L,
            0L,
            1440L,
            92_000L,
            1,
            "RELEASED",
            null,
            null,
            null,
            finance.subject(),
            NOW,
            "pout",
            null,
            "k-replay");
    when(settlements.findByIdempotencyKey("k-replay"))
        .thenReturn(Optional.empty(), Optional.of(released));
    when(settlements.findById(settlementId)).thenReturn(Optional.of(pending));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot("12", "B", "IFSC", "VERIFIED")));
    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(false);
    assertThat(service.release(finance, settlementId, null, "k-replay").get("status"))
        .isEqualTo("RELEASED");
  }

  @Test
  void belowThresholdNullNotesAndAlreadyCarried() {
    SettlementRecord tiny =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            1000L,
            new BigDecimal("8"),
            80L,
            0L,
            0L,
            500L,
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
    when(settlements.findByIdempotencyKey("tiny")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(tiny));
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "tiny"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AMOUNT_BELOW_THRESHOLD");

    SettlementRecord below =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            1000L,
            new BigDecimal("8"),
            80L,
            0L,
            0L,
            500L,
            1,
            "BELOW_THRESHOLD_CARRIED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(settlements.findByIdempotencyKey("below")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(below));
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "below"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AMOUNT_BELOW_THRESHOLD");
  }

  @Test
  void mapsNonCashfreeAndCashfreeErrorCodes() {
    SettlementRecord pending =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            new BigDecimal("8"),
            8000L,
            0L,
            0L,
            92_000L,
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
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot("XXXX4521", "B", "IFSC", "VERIFIED")));
    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(settlements.findById(settlementId)).thenReturn(Optional.of(pending));

    when(settlements.findByIdempotencyKey("k4")).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new AppException("SETTLEMENT_CONFLICT", "x", 409))
        .when(cashfree_payouts)
        .initiatePayout(any());
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SETTLEMENT_CONFLICT");

    when(settlements.findByIdempotencyKey("k5")).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new AppException("CASHFREE_ERROR", "gw", 502))
        .when(cashfree_payouts)
        .initiatePayout(any());
    assertThatThrownBy(() -> service.release(finance, settlementId, null, "k5"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
  }

  @Test
  void bankNullFieldsShortMaskAndBlankPayoutId() {
    SettlementRecord row =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            null,
            8000L,
            0L,
            0L,
            92_000L,
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
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot(null, null, null, "VERIFIED")));
    when(settlements.lineItems(any(), any(), any(), any())).thenReturn(List.of());
    assertThat(service.getAdminDetail(finance, settlementId).get("pharmacy_bank")).isNotNull();

    when(settlements.findByIdempotencyKey("short")).thenReturn(Optional.empty());
    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("p", 4));
    when(settlements.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row), Optional.of(row));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot("12", "B", "IFSC", "VERIFIED")));
    assertThat(service.release(finance, settlementId, "n", "short").get("status"))
        .isEqualTo("PENDING");

    when(settlements.findByIdempotencyKey("nullmask")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row), Optional.of(row));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot(null, "B", "IFSC", "VERIFIED")));
    assertThat(service.release(finance, settlementId, "n", "nullmask").get("status"))
        .isEqualTo("PENDING");

    assertThat(new PharmacySettlementPort.ListResult(null, 0).settlements()).isEmpty();
  }

  @Test
  void listDefaultsAndBulkAndHoldNullReason() {
    SettlementRecord pending =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            null,
            8000L,
            0L,
            1440L,
            92_000L,
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
    when(settlements.list(any())).thenReturn(new ListResult(List.of(pending), 1));
    when(settlements.totals(any())).thenReturn(new Totals(1, 1, 0, 1));
    when(settlements.kpis(any(), any())).thenReturn(new KpiSnapshot(0, 0, 0, 0));
    assertThat(service.listAdmin(finance, null, null, null, 0, 0).meta().page()).isEqualTo(1);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    assertThat(service.listPharmacy(owner, " ", 0, 0).data().get("settlements"))
        .asList()
        .hasSize(1);

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    SettlementFacadeService.requireFinanceRole(superAdmin);
    assertThat(new SettlementFacadeService.PagedResult(null, null).data()).isEmpty();

    SettlementRecord ok =
        new SettlementRecord(
            UUID.randomUUID(),
            pharmacyId,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            50_000L,
            new BigDecimal("8"),
            4000L,
            0L,
            0L,
            46_000L,
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
    when(settlements.listPendingForBulk(anyLong(), eq(200))).thenReturn(List.of(ok));
    when(settlements.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(settlements.findById(ok.id())).thenReturn(Optional.of(ok), Optional.of(ok));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot("XXXX4521", "B", "IFSC", "VERIFIED")));
    when(settlements.claimForRelease(eq(ok.id()), any(), anyString(), any())).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("p", 4));
    when(settlements.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);
    Map<String, Object> bulk =
        service.releaseAll(finance, new BigDecimal("1000.00"), "n", "bulk-n");
    assertThat(bulk.get("released")).isEqualTo(1);

    assertThatThrownBy(() -> service.hold(finance, settlementId, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void remainingBranchMatrix() {
    SettlementRecord pending =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            null,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            new BigDecimal("8"),
            8000L,
            0L,
            0L,
            92_000L,
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
    SettlementRecord released =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            new BigDecimal("8"),
            8000L,
            0L,
            0L,
            92_000L,
            1,
            "RELEASED",
            null,
            null,
            null,
            finance.subject(),
            NOW,
            "p",
            null,
            "k");
    when(settlements.list(any())).thenReturn(new ListResult(List.of(pending, released), 2));
    when(settlements.totals(any())).thenReturn(new Totals(1, 1, 0, 1));
    when(settlements.kpis(any(), any())).thenReturn(new KpiSnapshot(0, 0, 0, 0));
    assertThat(service.listAdmin(finance, null, null, null, null, null).meta().limit())
        .isEqualTo(20);
    assertThat(service.listAdmin(finance, null, null, null, 2, 200).meta().limit()).isEqualTo(100);

    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    assertThat(service.listPharmacy(owner, null, null, null).meta().page()).isEqualTo(1);

    MedmatePrincipal ownerNoPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listPharmacy(ownerNoPharmacy, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> SettlementFacadeService.requireIdempotencyKey(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(settlements.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getAdminDetail(finance, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SETTLEMENT_NOT_FOUND");

    when(settlements.findById(settlementId)).thenReturn(Optional.of(pending));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot(null, "B", "IFSC", "VERIFIED")));
    when(settlements.lineItems(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                new LineItem(
                    UUID.randomUUID(), "MED-2", null, 1000L, new BigDecimal("8"), 80L, 10L, 910L)));
    assertThat(service.getAdminDetail(finance, settlementId).get("line_items")).asList().hasSize(1);

    when(settlements.listPendingForBulk(anyLong(), eq(200))).thenReturn(List.of(pending));
    when(settlements.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(settlements.findVerifiedBank(pharmacyId)).thenReturn(Optional.empty());
    Map<String, Object> bulk = service.releaseAll(finance, null, null, "bulk-null-name");
    assertThat(bulk.get("failed")).isEqualTo(1);
  }

  @Test
  void release_withTransactionManager_usesTransactionTemplate() {
    org.springframework.transaction.PlatformTransactionManager tm =
        mock(org.springframework.transaction.PlatformTransactionManager.class);
    org.springframework.transaction.TransactionStatus status =
        mock(org.springframework.transaction.TransactionStatus.class);
    when(tm.getTransaction(any())).thenReturn(status);
    SettlementFacadeService withTx =
        new SettlementFacadeService(
            settlements,
            cashfree_payouts,
            ledger,
            notifications,
            tcsRegister,
            Clock.fixed(NOW, ZoneOffset.UTC),
            tm);

    SettlementRecord pendingRow =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            120_000L,
            new BigDecimal("8"),
            8_000L,
            1_000L,
            0L,
            100_000L,
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
    SettlementRecord releasedRow =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            120_000L,
            new BigDecimal("8"),
            8_000L,
            1_000L,
            0L,
            100_000L,
            1,
            "RELEASED",
            null,
            null,
            null,
            finance.subject(),
            NOW,
            "pout_tx",
            null,
            "idem-tx");
    when(settlements.findByIdempotencyKey("idem-tx")).thenReturn(Optional.empty());
    when(settlements.findById(settlementId))
        .thenReturn(Optional.of(pendingRow), Optional.of(releasedRow));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot("XXXXXXXXXXXX1111", "SBI", "SBIN", "VERIFIED")));
    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("pout_tx", 4));
    when(settlements.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);

    Map<String, Object> result = withTx.release(finance, settlementId, "n", "idem-tx");
    assertThat(result.get("status")).isEqualTo("RELEASED");
    verify(tm, org.mockito.Mockito.atLeastOnce()).getTransaction(any());
    verify(tm, org.mockito.Mockito.atLeastOnce()).commit(status);
  }

  @Test
  void release_withProviderOps_replaysMarksAndAmbiguous() {
    com.nammamedmate.messaging.ProviderOperationStore ops =
        org.mockito.Mockito.mock(com.nammamedmate.messaging.ProviderOperationStore.class);
    SettlementFacadeService withOps =
        new SettlementFacadeService(
            settlements,
            cashfree_payouts,
            ledger,
            notifications,
            tcsRegister,
            Clock.fixed(NOW, ZoneOffset.UTC),
            null,
            ops);
    SettlementRecord row =
        new SettlementRecord(
            settlementId,
            pharmacyId,
            "P",
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            100_000L,
            new BigDecimal("8"),
            8000L,
            0L,
            0L,
            92_000L,
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
    when(settlements.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(settlements.findById(settlementId)).thenReturn(Optional.of(row));
    when(settlements.findVerifiedBank(pharmacyId))
        .thenReturn(Optional.of(new BankSnapshot("XXXX4521", "B", "IFSC", "VERIFIED")));
    when(settlements.claimForRelease(any(), any(), anyString(), any())).thenReturn(true);
    when(settlements.finalizeRelease(any(), any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);
    when(ops.find(eq("PAYOUT"), anyString())).thenReturn(Optional.empty());
    when(cashfree_payouts.initiatePayout(any())).thenReturn(new PayoutResult("pout_new", 4));

    assertThat(withOps.release(finance, settlementId, "n", "ops-new").get("status"))
        .isEqualTo("PENDING");
    verify(ops).ensurePending(eq("PAYOUT"), anyString(), eq("cashfree_payouts"));
    verify(ops).markSent(eq("PAYOUT"), anyString(), eq("pout_new"));
    verify(ops).markSucceeded(eq("PAYOUT"), anyString(), eq("pout_new"));

    when(ops.find(eq("PAYOUT"), anyString()))
        .thenReturn(
            Optional.of(
                new com.nammamedmate.messaging.ProviderOperationStore.Operation(
                    "PAYOUT", "settlement:" + settlementId, "pout_replay", "SENT")));
    assertThat(withOps.release(finance, settlementId, "n", "ops-replay").get("status"))
        .isEqualTo("PENDING");

    when(ops.find(eq("PAYOUT"), anyString())).thenReturn(Optional.empty());
    when(cashfree_payouts.initiatePayout(any())).thenThrow(new RuntimeException("gw down"));
    assertThatThrownBy(() -> withOps.release(finance, settlementId, "n", "ops-fail"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CASHFREE_PAYOUT_FAILED");
    verify(ops)
        .markAmbiguous(
            eq("PAYOUT"), anyString(), org.mockito.ArgumentMatchers.isNull(), anyString());
  }
}
