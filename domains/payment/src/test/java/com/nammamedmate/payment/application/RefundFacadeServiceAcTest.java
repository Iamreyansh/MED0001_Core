package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.adapter.out.client.StubCashfreeGatewayClient;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
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
class RefundFacadeServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock private RefundFinancePort refunds;
  @Mock private CustomerWalletPort wallets;
  @Mock private FinancialLedgerWriterPort ledger;
  @Mock private RefundNotificationPort notifications;

  private StubCashfreeGatewayClient cashfree;
  private RefundFacadeService service;
  private final UUID adminId = UUID.randomUUID();
  private final UUID customerId = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID refundId = UUID.randomUUID();
  private final MedmatePrincipal finance =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal support =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    cashfree = new StubCashfreeGatewayClient();
    service =
        new RefundFacadeService(
            refunds, cashfree, wallets, ledger, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private RefundRecord pendingSource(long amountPaise, Instant createdAt) {
    return new RefundRecord(
        refundId,
        orderId,
        "MED-20260724-018",
        customerId,
        "Priya S",
        "9876543210",
        "priya.s@example.com",
        amountPaise,
        49500L,
        5000L,
        "SOURCE",
        "PENDING",
        "PHARMACY_CANCELLED",
        null,
        "UPI",
        null,
        "pay_XXXXXXXXXXXX",
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        createdAt);
  }

  @Test
  void ac002_largeRefundStaysPendingUntilAdminProcess() {
    RefundRecord row = pendingSource(60_000L, NOW);
    when(refunds.findById(refundId)).thenReturn(Optional.of(row));
    when(refunds.claimForProcess(eq(refundId), eq(adminId), any(), eq(NOW))).thenReturn(true);
    when(refunds.finalizeGatewayProcess(eq(refundId), anyString(), any(), eq(NOW)))
        .thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row))
        .thenReturn(
            Optional.of(
                new RefundRecord(
                    refundId,
                    orderId,
                    "MED-20260724-018",
                    customerId,
                    "Priya S",
                    "9876543210",
                    "priya.s@example.com",
                    60_000L,
                    60000L,
                    0L,
                    "SOURCE",
                    "INITIATED",
                    "PHARMACY_CANCELLED",
                    "Approved",
                    "UPI",
                    "rfnd_x",
                    "pay_XXXXXXXXXXXX",
                    null,
                    false,
                    null,
                    adminId,
                    NOW,
                    null,
                    LocalDate.parse("2026-07-31"),
                    null,
                    NOW)));

    Map<String, Object> result = service.process(finance, refundId, "Approved");
    assertThat(result.get("status")).isEqualTo("PROCESSING");
    assertThat(result.get("gateway_refund_id")).isNotNull();
    verify(refunds).finalizeGatewayProcess(eq(refundId), anyString(), any(), eq(NOW));
  }

  @Test
  void ac003_codWalletCompletedViaProcessPath() {
    RefundRecord row =
        new RefundRecord(
            refundId,
            orderId,
            "MED-1",
            customerId,
            "A",
            "9",
            "a@b.c",
            10000L,
            10000L,
            0L,
            "WALLET",
            "PENDING",
            "COD",
            null,
            "COD",
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
    when(refunds.findById(refundId)).thenReturn(Optional.of(row));
    when(refunds.claimForProcess(eq(refundId), eq(adminId), any(), eq(NOW))).thenReturn(true);
    when(wallets.systemCredit(eq(customerId), eq(10000L), eq("REFUND"), any(), any(), any()))
        .thenReturn(Map.of("transaction_id", UUID.randomUUID()));
    when(refunds.markWalletCompleted(eq(refundId), any(), eq(adminId), any(), eq(NOW)))
        .thenReturn(true);
    when(refunds.findById(refundId))
        .thenReturn(Optional.of(row))
        .thenReturn(
            Optional.of(
                new RefundRecord(
                    refundId,
                    orderId,
                    "MED-1",
                    customerId,
                    "A",
                    "9",
                    "a@b.c",
                    10000L,
                    10000L,
                    0L,
                    "WALLET",
                    "PROCESSED",
                    "COD",
                    null,
                    "COD",
                    null,
                    null,
                    UUID.randomUUID(),
                    false,
                    null,
                    adminId,
                    NOW,
                    NOW,
                    null,
                    null,
                    NOW)));

    Map<String, Object> result = service.process(finance, refundId, null);
    assertThat(result.get("status")).isEqualTo("COMPLETED");
    assertThat(result.get("refund_to")).isEqualTo("WALLET");
    verify(ledger)
        .append(eq("REFUND"), eq(refundId), eq("REFUND"), eq(0L), eq(10000L), any(), any());
    verify(notifications).refundCompleted(customerId, refundId, orderId, 10000L);
  }

  @Test
  void ac004_webhookMarksCompletedAndNotifies() throws Exception {
    RefundRecord row =
        new RefundRecord(
            refundId,
            orderId,
            "MED-1",
            customerId,
            "A",
            "9",
            "a@b.c",
            45000L,
            49500L,
            5000L,
            "SOURCE",
            "INITIATED",
            "PHARMACY_CANCELLED",
            null,
            "UPI",
            "rfnd_XXXXXXXXXXXX",
            "pay_x",
            null,
            true,
            null,
            adminId,
            NOW,
            null,
            LocalDate.parse("2026-07-29"),
            null,
            NOW);
    when(refunds.findByGatewayRefundId("rfnd_XXXXXXXXXXXX")).thenReturn(Optional.of(row));
    when(refunds.markCompleted(refundId, NOW)).thenReturn(true);

    var root =
        new ObjectMapper()
            .readTree(
                """
                {"event":"refund.processed","payload":{"refund":{"entity":{"id":"rfnd_XXXXXXXXXXXX","payment_id":"pay_x"}}}}
                """);
    Map<String, Object> result = service.completeFromWebhook(root);
    assertThat(result.get("status")).isEqualTo("COMPLETED");
    assertThat(result.get("processed")).isEqualTo(true);
    verify(ledger)
        .append(eq("REFUND"), eq(refundId), eq("REFUND"), eq(0L), eq(45000L), any(), any());
    verify(notifications).refundCompleted(customerId, refundId, orderId, 45000L);
  }

  @Test
  void ac006_listShowsOverduePending() {
    Instant old = NOW.minusSeconds(25 * 3600);
    when(refunds.list(any())).thenReturn(new ListResult(List.of(pendingSource(60000L, old)), 1));
    when(refunds.kpis(any(), any(), any())).thenReturn(new KpiSnapshot(1, 60000, 0, 0, 1));

    var result = service.listAdmin(finance, "PENDING", null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("refunds");
    assertThat(items.getFirst().get("is_overdue")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) result.data().get("kpi_chips");
    assertThat(kpi.get("overdue_count")).isEqualTo(1L);
  }

  @Test
  void ac007_reprocessCompletedReturns409() {
    RefundRecord done =
        new RefundRecord(
            refundId,
            orderId,
            "MED-1",
            customerId,
            "A",
            "9",
            "a@b.c",
            1000L,
            1000L,
            0L,
            "SOURCE",
            "PROCESSED",
            "X",
            null,
            "UPI",
            "rfnd_1",
            "pay_1",
            null,
            true,
            null,
            adminId,
            NOW,
            NOW,
            null,
            null,
            NOW);
    when(refunds.findById(refundId)).thenReturn(Optional.of(done));
    assertThatThrownBy(() -> service.process(finance, refundId, "again"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("REFUND_ALREADY_PROCESSED");
              assertThat(ae.httpStatus()).isEqualTo(409);
            });
    verify(refunds, never()).claimForProcess(any(), any(), any(), any());
  }

  @Test
  void ac005_partialDetailFlagsIsPartial() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(pendingSource(45000L, NOW)));
    Map<String, Object> detail = service.getAdminDetail(support, refundId);
    assertThat(detail.get("is_partial")).isEqualTo(true);
    assertThat(detail.get("refund_amount"))
        .isEqualTo(java.math.BigDecimal.valueOf(450.00).setScale(2));
  }

  @Test
  void ac008_hybridDetailShowsWalletAndGatewayPortions() {
    when(refunds.findById(refundId)).thenReturn(Optional.of(pendingSource(44500L, NOW)));
    Map<String, Object> detail = service.getAdminDetail(finance, refundId);
    assertThat(detail.get("wallet_portion_original"))
        .isEqualTo(java.math.BigDecimal.valueOf(50.00).setScale(2));
    assertThat(detail.get("gateway_refund_amount"))
        .isEqualTo(java.math.BigDecimal.valueOf(445.00).setScale(2));
  }

  @Test
  void customerListAndMessages() {
    when(refunds.listForCustomer(eq(customerId), eq(20), eq(0)))
        .thenReturn(
            new ListResult(
                List.of(
                    new RefundRecord(
                        refundId,
                        orderId,
                        "MED-1",
                        customerId,
                        "A",
                        "9",
                        "a@b.c",
                        45000L,
                        49500L,
                        0L,
                        "SOURCE",
                        "INITIATED",
                        "X",
                        null,
                        "UPI",
                        "rfnd_1",
                        "pay_1",
                        null,
                        true,
                        null,
                        null,
                        NOW,
                        null,
                        LocalDate.parse("2026-07-29"),
                        null,
                        NOW)),
                1));

    var page = service.listCustomer(customer, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.data().get("refunds");
    assertThat(items.getFirst().get("message").toString()).contains("3-5 business days");
  }

  @Test
  void notFoundAndRoleGuards() {
    when(refunds.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getAdminDetail(finance, refundId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFUND_NOT_FOUND");
    assertThatThrownBy(() -> service.listAdmin(customer, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.process(support, refundId, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listCustomer(finance, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void statusAndRefundToFiltersAndBusinessDays() {
    assertThat(RefundStatuses.toApiStatus("INITIATED")).isEqualTo("PROCESSING");
    assertThat(RefundStatuses.toApiRefundTo("SOURCE")).isEqualTo("SOURCE_ACCOUNT");
    assertThat(RefundFacadeService.addBusinessDays(LocalDate.parse("2026-07-24"), 5))
        .isEqualTo(LocalDate.parse("2026-07-31"));
    assertThatThrownBy(() -> service.listAdmin(finance, "NOPE", null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listAdmin(finance, null, "NOPE", null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void cashfreeFailureMarksFailed() {
    RefundRecord row = pendingSource(60000L, NOW);
    when(refunds.findById(refundId)).thenReturn(Optional.of(row));
    when(refunds.claimForProcess(eq(refundId), eq(adminId), any(), eq(NOW))).thenReturn(true);
    RefundFacadeService failing =
        new RefundFacadeService(
            refunds,
            new StubCashfreeGatewayClient("k", "s", "w", true),
            wallets,
            ledger,
            notifications,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> failing.process(finance, refundId, "go"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CASHFREE_REFUND_FAILED");
    verify(refunds).markProcessFailed(eq(refundId), anyString(), eq(NOW));
  }
}
