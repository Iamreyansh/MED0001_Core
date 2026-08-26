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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.adapter.out.client.StubRazorpayGatewayClient;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import com.nammamedmate.payment.application.port.out.RefundFinancePort;
import com.nammamedmate.payment.application.port.out.RefundFinancePort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.RefundFinancePort.ListResult;
import com.nammamedmate.payment.application.port.out.RefundFinancePort.RefundRecord;
import com.nammamedmate.payment.application.port.out.RefundNotificationPort;
import com.nammamedmate.payment.domain.RefundStatuses;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundFacadeCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock RefundFinancePort refunds;
  @Mock CustomerWalletPort wallets;
  @Mock FinancialLedgerWriterPort ledger;
  @Mock RefundNotificationPort notifications;

  RefundFacadeService service;
  UUID adminId = UUID.randomUUID();
  UUID customerId = UUID.randomUUID();
  UUID orderId = UUID.randomUUID();
  UUID refundId = UUID.randomUUID();
  MedmatePrincipal finance =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new RefundFacadeService(
            refunds,
            new StubRazorpayGatewayClient(),
            wallets,
            ledger,
            notifications,
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
  }

  private RefundRecord row(String status, String refundTo) {
    return new RefundRecord(
        refundId,
        orderId,
        "MED-1",
        customerId,
        "N",
        "9",
        "e@x.com",
        1000L,
        1000L,
        0L,
        refundTo,
        status,
        "R",
        "note",
        "WALLET",
        "rfnd_1",
        "pay_1",
        null,
        false,
        null,
        adminId,
        NOW,
        NOW,
        LocalDate.parse("2026-07-29"),
        null,
        NOW);
  }

  @Test
  void webhookIdempotentAndMissingId() throws Exception {
    when(refunds.findByRazorpayRefundId("rfnd_1"))
        .thenReturn(Optional.of(row("PROCESSED", "SOURCE")));
    var root =
        new ObjectMapper()
            .readTree(
                "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\"}}}}");
    assertThat(service.completeFromWebhook(root).get("processed")).isEqualTo(false);

    var noId =
        new ObjectMapper()
            .readTree(
                "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"payment_id\":\"pay\"}}}}");
    assertThat(service.completeFromWebhook(noId).get("processed")).isEqualTo(true);

    when(refunds.findByRazorpayRefundId("rfnd_miss")).thenReturn(Optional.empty());
    var miss =
        new ObjectMapper()
            .readTree(
                "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_miss\"}}}}");
    assertThat(service.completeFromWebhook(miss).get("processed")).isEqualTo(true);
  }

  @Test
  void claimRaceAndMissingPaymentId() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "SOURCE")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.process(finance, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFUND_ALREADY_PROCESSED");

    RefundRecord noPay =
        new RefundRecord(
            refundId,
            orderId,
            "MED-1",
            customerId,
            "N",
            "9",
            "e",
            1000L,
            1000L,
            0L,
            "SOURCE",
            "PENDING",
            "R",
            null,
            "UPI",
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    when(refunds.findById(refundId)).thenReturn(Optional.of(noPay));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    assertThatThrownBy(() -> service.process(finance, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");
    verify(refunds).markProcessFailed(eq(refundId), anyString(), eq(NOW));
  }

  @Test
  void listFiltersFromDateAndCustomerMessages() {
    when(refunds.list(any())).thenReturn(new ListResult(List.of(row("PENDING", "SOURCE")), 1));
    when(refunds.kpis(any(), any(), any())).thenReturn(new KpiSnapshot(1, 1000, 2, 1, 0));
    var page =
        service.listAdmin(
            finance, "PENDING", "SOURCE_ACCOUNT", LocalDate.parse("2026-07-01"), 0, 0);
    assertThat(page.meta().page()).isEqualTo(1);
    assertThat(page.meta().limit()).isEqualTo(20);

    when(refunds.listForCustomer(eq(customerId), eq(20), eq(0)))
        .thenReturn(new ListResult(List.of(row("PROCESSED", "WALLET")), 1));
    MedmatePrincipal cust =
        new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    var custPage = service.listCustomer(cust, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) custPage.data().get("refunds");
    assertThat(items.getFirst().get("message").toString()).contains("wallet");

    assertThat(RefundStatuses.customerMessage("FAILED", "SOURCE_ACCOUNT")).contains("support");
    assertThat(RefundStatuses.customerMessage("PENDING", "SOURCE_ACCOUNT")).contains("3-5");
    assertThat(RefundStatuses.toStorageStatusFilter("COMPLETED")).isEqualTo("PROCESSED");
    assertThat(RefundStatuses.toStorageRefundToFilter("WALLET")).isEqualTo("WALLET");
    assertThat(RefundStatuses.toApiStatus(null)).isEqualTo("PENDING");
    assertThat(RefundStatuses.toApiRefundTo(null)).isEqualTo("WALLET");
    assertThat(RefundStatuses.customerMessage("X", "Y")).contains("processed");
    assertThat(RefundFacadeService.istOffset()).isNotNull();
  }

  @Test
  void unauthorizedNullPrincipal() {
    assertThatThrownBy(() -> service.listAdmin(null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.process(null, refundId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.listCustomer(null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.process(finance, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFUND_NOT_FOUND");
  }

  @Test
  void walletCreditFailureMarksFailed() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    when(wallets.systemCredit(any(), any(Long.class), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));
    assertThatThrownBy(() -> service.process(finance, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    verify(refunds).markProcessFailed(eq(refundId), anyString(), eq(NOW));
  }

  @Test
  void walletAppExceptionMarksFailed() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    when(wallets.systemCredit(any(), any(Long.class), any(), any(), any(), any()))
        .thenThrow(new AppException("WALLET_LOCKED", "locked", 422));
    assertThatThrownBy(() -> service.process(finance, refundId, "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("WALLET_LOCKED");
    verify(refunds).markProcessFailed(eq(refundId), anyString(), eq(NOW));
  }

  @Test
  void markWalletCompletedConflict() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    when(wallets.systemCredit(any(), any(Long.class), any(), any(), any(), any()))
        .thenReturn(Map.of("transaction_id", "not-a-uuid"));
    when(refunds.markWalletCompleted(any(), any(), any(), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.process(finance, refundId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFUND_ALREADY_PROCESSED");
  }

  @Test
  void finalizeGatewayConflictAndGatewayErrors() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "SOURCE")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    RazorpayGatewayPort gateway = org.mockito.Mockito.mock(RazorpayGatewayPort.class);
    when(gateway.refund(anyString(), anyLong()))
        .thenReturn(new RazorpayGatewayPort.RefundResult("rfnd_1", 1000L));
    when(refunds.finalizeGatewayProcess(any(), any(), any(), any())).thenReturn(false);
    RefundFacadeService withMockGw =
        new RefundFacadeService(
            refunds, gateway, wallets, ledger, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> withMockGw.process(finance, refundId, "go"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFUND_ALREADY_PROCESSED");
    verify(refunds).attachGatewayRefundId(eq(refundId), eq("rfnd_1"), eq(NOW));
    verify(refunds, never()).markProcessFailed(eq(refundId), anyString(), eq(NOW));

    RazorpayGatewayPort boom = org.mockito.Mockito.mock(RazorpayGatewayPort.class);
    org.mockito.Mockito.doThrow(new AppException("VALIDATION_ERROR", "bad", 400))
        .when(boom)
        .refund(anyString(), anyLong());
    RefundFacadeService svc2 =
        new RefundFacadeService(
            refunds, boom, wallets, ledger, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
    when(refunds.finalizeGatewayProcess(any(), any(), any(), any())).thenReturn(true);
    assertThatThrownBy(() -> svc2.process(finance, refundId, "go"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    org.mockito.Mockito.doThrow(new IllegalStateException("x"))
        .when(boom)
        .refund(anyString(), anyLong());
    assertThatThrownBy(() -> svc2.process(finance, refundId, "go"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");
  }

  @Test
  void transactionTemplateAndClaimAlreadyMoved() {
    org.springframework.transaction.PlatformTransactionManager tm =
        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
    org.springframework.transaction.TransactionStatus status =
        org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class);
    when(tm.getTransaction(any())).thenReturn(status);
    RefundFacadeService withTx =
        new RefundFacadeService(
            refunds,
            new StubRazorpayGatewayClient(),
            wallets,
            ledger,
            notifications,
            Clock.fixed(NOW, ZoneOffset.UTC),
            tm);
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "SOURCE")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(false);
    // after failed claim, current is no longer PENDING
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row("PENDING", "SOURCE")))
        .thenReturn(Optional.of(row("INITIATED", "SOURCE")));
    assertThatThrownBy(() -> withTx.process(finance, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFUND_ALREADY_PROCESSED");
    verify(tm).getTransaction(any());
  }

  @Test
  void processResponseAndDetailEdgeBranches() {
    RefundRecord withProcessed =
        new RefundRecord(
            refundId,
            orderId,
            "MED",
            null,
            "N",
            "9",
            "e",
            1000L,
            2000L,
            0L,
            "SOURCE",
            "PENDING",
            "R",
            "note",
            " ",
            "rfnd",
            "pay",
            null,
            true,
            null,
            adminId,
            NOW,
            null,
            LocalDate.parse("2026-07-29"),
            null,
            NOW.minusSeconds(90000));
    when(refunds.findById(refundId)).thenReturn(Optional.of(withProcessed));
    Map<String, Object> detail = service.getAdminDetail(finance, refundId);
    assertThat(detail.get("is_overdue")).isEqualTo(true);
    assertThat(detail.get("expected_by")).isEqualTo("2026-07-29");
    assertThat(detail.get("payment_method")).isEqualTo("UPI");

    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    when(refunds.finalizeGatewayProcess(any(), any(), any(), any())).thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(withProcessed))
        .thenReturn(
            Optional.of(
                new RefundRecord(
                    refundId,
                    orderId,
                    "MED",
                    null,
                    "N",
                    "9",
                    "e",
                    1000L,
                    2000L,
                    0L,
                    "SOURCE",
                    "INITIATED",
                    "R",
                    "note",
                    null,
                    "rfnd_done",
                    "pay",
                    null,
                    true,
                    null,
                    adminId,
                    null,
                    null,
                    LocalDate.parse("2026-07-29"),
                    null,
                    NOW)));
    Map<String, Object> processed = service.process(finance, refundId, "ok");
    assertThat(processed.get("processed_by")).isEqualTo(adminId.toString());
    assertThat(processed.get("processed_at")).isNull();

    // processResponse: processedBy null on row → use principal; processed_at non-null
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    when(refunds.finalizeGatewayProcess(any(), any(), any(), any())).thenReturn(true);
    RefundRecord pendingNoProcessor =
        new RefundRecord(
            refundId, orderId, "MED", null, "N", "9", "e", 1000L, 2000L, 0L, "SOURCE", "PENDING",
            "R", "note", "UPI", null, "pay", null, false, null, null, null, null, null, null, NOW);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(pendingNoProcessor))
        .thenReturn(
            Optional.of(
                new RefundRecord(
                    refundId,
                    orderId,
                    "MED",
                    null,
                    "N",
                    "9",
                    "e",
                    1000L,
                    2000L,
                    0L,
                    "SOURCE",
                    "INITIATED",
                    "R",
                    "note",
                    "UPI",
                    "rfnd_x",
                    "pay",
                    null,
                    false,
                    null,
                    null,
                    NOW,
                    null,
                    LocalDate.parse("2026-07-29"),
                    null,
                    NOW)));
    Map<String, Object> again = service.process(finance, refundId, "again");
    assertThat(again.get("processed_by")).isEqualTo(adminId.toString());
    assertThat(again.get("processed_at")).isEqualTo(NOW.toString());

    when(refunds.findByRazorpayRefundId("rfnd_c")).thenReturn(Optional.of(withProcessed));
    when(refunds.markCompleted(refundId, NOW)).thenReturn(true);
    try {
      service.completeFromWebhook(
          new ObjectMapper()
              .readTree("{\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_c\"}}}}"));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    verify(ledger)
        .append(eq("REFUND"), eq(refundId), eq("REFUND"), eq(0L), eq(1000L), anyString(), any());

    when(refunds.listForCustomer(eq(customerId), eq(20), eq(0)))
        .thenReturn(new ListResult(List.of(withProcessed), 1));
    MedmatePrincipal cust =
        new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    // customer list uses principal subject — withProcessed has null customerId but list is by
    // subject
    service.listCustomer(cust, 1, 20);
    assertThat(RefundFacadeService.istOffset()).isNotNull();
  }

  @Test
  void extractUuidAndCustomerItemNonNullDates() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    UUID tx = UUID.randomUUID();
    when(wallets.systemCredit(any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(Map.of("transaction_id", tx));
    when(refunds.markWalletCompleted(any(), any(), any(), any(), any())).thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row("PENDING", "WALLET")))
        .thenReturn(
            Optional.of(
                new RefundRecord(
                    refundId,
                    orderId,
                    "MED",
                    customerId,
                    "N",
                    "9",
                    "e",
                    1000L,
                    1000L,
                    0L,
                    "WALLET",
                    "PROCESSED",
                    "R",
                    null,
                    "UPI",
                    null,
                    null,
                    tx,
                    false,
                    null,
                    null,
                    NOW,
                    NOW,
                    null,
                    null,
                    NOW)));
    assertThat(service.process(finance, refundId, "n").get("processed_by"))
        .isEqualTo(adminId.toString());

    when(wallets.systemCredit(any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(Map.of("transaction_id", tx.toString()));
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.markWalletCompleted(any(), any(), any(), any(), any())).thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row("PENDING", "WALLET")))
        .thenReturn(Optional.of(row("PROCESSED", "WALLET")));
    service.process(finance, refundId, "n2");

    when(wallets.systemCredit(any(), anyLong(), any(), any(), any(), any())).thenReturn(Map.of());
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.markWalletCompleted(any(), any(), any(), any(), any())).thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row("PENDING", "WALLET")))
        .thenReturn(Optional.of(row("PROCESSED", "WALLET")));
    service.process(finance, refundId, "n3");

    when(wallets.systemCredit(any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(Map.of("transaction_id", "not-a-uuid"));
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.markWalletCompleted(any(), any(), any(), any(), any())).thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row("PENDING", "WALLET")))
        .thenReturn(Optional.of(row("PROCESSED", "WALLET")));
    service.process(finance, refundId, "n4");

    RefundRecord dated =
        new RefundRecord(
            refundId,
            orderId,
            "MED",
            customerId,
            "N",
            "9",
            "e",
            1000L,
            1000L,
            0L,
            "SOURCE",
            "INITIATED",
            "R",
            null,
            "UPI",
            "rfnd",
            "pay",
            null,
            true,
            null,
            null,
            NOW,
            null,
            LocalDate.parse("2026-07-29"),
            null,
            NOW);
    when(refunds.listForCustomer(eq(customerId), eq(20), eq(0)))
        .thenReturn(new ListResult(List.of(dated), 1));
    MedmatePrincipal cust =
        new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    var page = service.listCustomer(cust, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.data().get("refunds");
    assertThat(items.getFirst().get("expected_by")).isEqualTo("2026-07-29");
    assertThat(items.getFirst().get("created_at")).isEqualTo(NOW.toString());
    assertThat(RefundFacadeService.istOffset()).isNotNull();

    RefundRecord sparse =
        new RefundRecord(
            refundId,
            orderId,
            null,
            null,
            null,
            null,
            null,
            1000L,
            1000L,
            0L,
            "WALLET",
            "PROCESSED",
            "R",
            null,
            "WALLET",
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(refunds.findById(refundId)).thenReturn(Optional.of(sparse));
    Map<String, Object> detail = service.getAdminDetail(finance, refundId);
    assertThat(detail.get("payment_method")).isEqualTo("WALLET_ONLY");

    when(refunds.list(any())).thenReturn(new ListResult(List.of(sparse), 1));
    when(refunds.kpis(any(), any(), any())).thenReturn(new KpiSnapshot(0, 0, 0, 0, 0));
    service.listAdmin(finance, null, "WALLET", null, 1, 5);

    when(refunds.listForCustomer(eq(customerId), eq(5), eq(0)))
        .thenReturn(new ListResult(List.of(row("FAILED", "SOURCE")), 1));
    service.listCustomer(cust, 1, 5);

    assertThat(new RefundFacadeService.PagedResult(null, PaginationMeta.of(1, 1, 0)).data())
        .isEmpty();
    assertThat(RefundStatuses.toStorageStatusFilter("FAILED")).isEqualTo("FAILED");
    assertThat(RefundStatuses.toApiStatus("")).isEqualTo("PENDING");
    assertThat(RefundStatuses.toApiRefundTo("")).isEqualTo("WALLET");
  }

  @Test
  void remainingBranchCoverage() throws Exception {
    when(refunds.list(any())).thenReturn(new ListResult(List.of(), 0));
    when(refunds.kpis(any(), any(), any())).thenReturn(new KpiSnapshot(0, 0, 0, 0, 0));
    // page/limit null branches + blank filters + clamp
    assertThat(service.listAdmin(finance, " ", " ", null, null, null).meta().page()).isEqualTo(1);
    assertThat(service.listAdmin(finance, null, null, null, 2, 500).meta().limit()).isEqualTo(100);

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    MedmatePrincipal support =
        new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "SOURCE")));
    service.listAdmin(superAdmin, null, null, null, 1, 20);
    service.getAdminDetail(support, refundId);
    assertThatThrownBy(() -> service.process(support, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    // blank razorpay payment id
    RefundRecord blankPay =
        new RefundRecord(
            refundId,
            orderId,
            "MED",
            customerId,
            "N",
            "9",
            "e",
            1000L,
            1000L,
            0L,
            "SOURCE",
            "PENDING",
            "R",
            null,
            null,
            null,
            "   ",
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    when(refunds.findById(refundId)).thenReturn(Optional.of(blankPay));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    assertThatThrownBy(() -> service.process(finance, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    RazorpayGatewayPort boom = org.mockito.Mockito.mock(RazorpayGatewayPort.class);
    org.mockito.Mockito.doThrow(new AppException("RAZORPAY_ERROR", "gw", 502))
        .when(boom)
        .refund(anyString(), anyLong());
    RefundFacadeService svc =
        new RefundFacadeService(
            refunds, boom, wallets, ledger, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "SOURCE")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    assertThatThrownBy(() -> svc.process(finance, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    // claim fails while still PENDING
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "SOURCE")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.process(finance, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFUND_ALREADY_PROCESSED");

    // webhook markCompleted false + blank refund id text
    when(refunds.findByRazorpayRefundId("rfnd_skip"))
        .thenReturn(Optional.of(row("INITIATED", "SOURCE")));
    when(refunds.markCompleted(refundId, NOW)).thenReturn(false);
    service.completeFromWebhook(
        new ObjectMapper()
            .readTree(
                "{\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_skip\",\"payment_id\":\"p\"}}}}"));
    assertThat(
            service
                .completeFromWebhook(
                    new ObjectMapper()
                        .readTree(
                            "{\"payload\":{\"refund\":{\"entity\":{\"id\":\"   \",\"payment_id\":null}}}}"))
                .get("processed"))
        .isEqualTo(true);

    // list item overdue false (fresh PENDING) + customer null dates + full refund detail
    RefundRecord fresh =
        new RefundRecord(
            refundId,
            orderId,
            "MED",
            customerId,
            "N",
            "9",
            "e",
            2000L,
            2000L,
            0L,
            "SOURCE",
            "PENDING",
            "R",
            null,
            "",
            null,
            "pay",
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    when(refunds.list(any())).thenReturn(new ListResult(List.of(fresh), 1));
    when(refunds.findById(refundId)).thenReturn(Optional.of(fresh));
    Map<String, Object> detail = service.getAdminDetail(finance, refundId);
    assertThat(detail.get("is_partial")).isEqualTo(false);
    assertThat(detail.get("is_overdue")).isEqualTo(false);
    service.listAdmin(finance, null, null, null, 1, 20);

    RefundRecord zeroTotal =
        new RefundRecord(
            refundId,
            orderId,
            "MED",
            customerId,
            "N",
            "9",
            "e",
            500L,
            0L,
            0L,
            "SOURCE",
            "INITIATED",
            "R",
            null,
            null,
            "rfnd",
            "pay",
            null,
            false,
            null,
            null,
            NOW,
            null,
            null,
            null,
            NOW.minusSeconds(90000));
    when(refunds.findById(refundId)).thenReturn(Optional.of(zeroTotal));
    Map<String, Object> zt = service.getAdminDetail(finance, refundId);
    assertThat(zt.get("is_partial")).isEqualTo(false);
    assertThat(zt.get("is_overdue")).isEqualTo(false);
    assertThat(zt.get("payment_method")).isEqualTo("UPI");

    // ADMIN_SUPER write path
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "WALLET")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    when(wallets.systemCredit(any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(Map.of("transaction_id", UUID.randomUUID()));
    when(refunds.markWalletCompleted(any(), any(), any(), any(), any())).thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row("PENDING", "WALLET")))
        .thenReturn(Optional.of(row("PROCESSED", "WALLET")));
    assertThat(service.process(superAdmin, refundId, "super").get("status")).isEqualTo("COMPLETED");

    RefundRecord pendingNoCreated =
        new RefundRecord(
            refundId,
            orderId,
            "MED",
            customerId,
            "N",
            "9",
            "e",
            1000L,
            1000L,
            0L,
            "SOURCE",
            "PENDING",
            "R",
            null,
            "UPI",
            null,
            "pay",
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(refunds.list(any())).thenReturn(new ListResult(List.of(pendingNoCreated), 1));
    when(refunds.findById(refundId)).thenReturn(Optional.of(pendingNoCreated));
    assertThat(service.getAdminDetail(finance, refundId).get("is_overdue")).isEqualTo(false);
    service.listAdmin(finance, null, null, null, 1, 20);

    RefundRecord noDates =
        new RefundRecord(
            refundId,
            orderId,
            "MED",
            customerId,
            "N",
            "9",
            "e",
            1000L,
            1000L,
            0L,
            "SOURCE",
            "PENDING",
            "R",
            null,
            "CARD",
            null,
            "pay",
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(refunds.listForCustomer(eq(customerId), eq(20), eq(0)))
        .thenReturn(new ListResult(List.of(noDates), 1));
    MedmatePrincipal cust =
        new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    var custPage = service.listCustomer(cust, 0, 0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) custPage.data().get("refunds");
    assertThat(items.getFirst().get("expected_by")).isNull();
    assertThat(items.getFirst().get("created_at")).isNull();

    assertThat(RefundStatuses.toStorageStatusFilter(null)).isNull();
  }

  @Test
  void process_withProviderOps_replaysAndMarksSent() {
    com.nammamedmate.messaging.ProviderOperationStore ops =
        org.mockito.Mockito.mock(com.nammamedmate.messaging.ProviderOperationStore.class);
    RazorpayGatewayPort gateway = org.mockito.Mockito.mock(RazorpayGatewayPort.class);
    RefundFacadeService withOps =
        new RefundFacadeService(
            refunds,
            gateway,
            wallets,
            ledger,
            notifications,
            Clock.fixed(NOW, ZoneOffset.UTC),
            null,
            ops);
    when(refunds.findById(refundId)).thenReturn(Optional.of(row("PENDING", "SOURCE")));
    when(refunds.claimForProcess(any(), any(), any(), any())).thenReturn(true);
    when(refunds.finalizeGatewayProcess(any(), any(), any(), any())).thenReturn(true);
    when(ops.find(eq("REFUND"), anyString())).thenReturn(Optional.empty());
    when(gateway.refund(anyString(), anyLong()))
        .thenReturn(new RazorpayGatewayPort.RefundResult("rfnd_new", 1000L));

    assertThat(withOps.process(finance, refundId, "go")).isNotNull();
    verify(ops).ensurePending(eq("REFUND"), anyString(), eq("razorpay"));
    verify(ops).markSent(eq("REFUND"), anyString(), eq("rfnd_new"));

    when(ops.find(eq("REFUND"), anyString()))
        .thenReturn(
            Optional.of(
                new com.nammamedmate.messaging.ProviderOperationStore.Operation(
                    "REFUND", "refund:" + refundId, "rfnd_replay", "SENT")));
    assertThat(withOps.process(finance, refundId, "go")).isNotNull();
    verify(gateway, org.mockito.Mockito.times(1)).refund(anyString(), anyLong());
  }
}
