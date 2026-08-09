package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletFacadeServiceAcTest {

  @Mock private CustomerWalletPort wallets;
  @Mock private FinancialLedgerWriterPort ledger;
  private WalletFacadeService service;
  private final UUID customerId = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID txId = UUID.randomUUID();
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service = new WalletFacadeService(wallets, ledger);
  }

  @Test
  void canAdminCredit_roles() {
    assertThat(WalletFacadeService.canAdminCredit(null)).isFalse();
    assertThat(WalletFacadeService.canAdminCredit(admin)).isTrue();
    assertThat(
            WalletFacadeService.canAdminCredit(
                new MedmatePrincipal(
                    UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j")))
        .isTrue();
    assertThat(
            WalletFacadeService.canAdminCredit(
                new MedmatePrincipal(
                    UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j")))
        .isTrue();
    assertThat(
            WalletFacadeService.canAdminCredit(
                new MedmatePrincipal(
                    UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j")))
        .isFalse();
  }

  @Test
  void ac001_idempotentDebit_alreadyProcessedSkipsLedger() {
    Map<String, Object> first = debitResult(false);
    Map<String, Object> replay = debitResult(true);
    when(wallets.debit(eq(customerId), eq(orderId), eq(5000L), eq("key-1"), anyString()))
        .thenReturn(first, replay);

    assertThat(
            service
                .debit(customerId, new BigDecimal("50.00"), orderId, "key-1")
                .get("already_processed"))
        .isEqualTo(false);
    assertThat(
            service
                .debit(customerId, new BigDecimal("50.00"), orderId, "key-1")
                .get("already_processed"))
        .isEqualTo(true);

    verify(ledger)
        .append(eq("WALLET_DEBIT"), eq(txId), eq("WALLET"), eq(0L), eq(5000L), anyString(), any());
  }

  @Test
  void ac002_insufficientBalance_propagates() {
    when(wallets.debit(any(), any(), anyLong(), anyString(), anyString()))
        .thenThrow(new AppException("INSUFFICIENT_BALANCE", "short", 422));
    assertThatThrownBy(() -> service.debit(customerId, 200, orderId, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BALANCE");
  }

  @Test
  void ac003_invalidAmount_rejected() {
    assertThatThrownBy(() -> service.debit(customerId, 0, orderId, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
  }

  @Test
  void ac006_adminCreditExceedsLimit() {
    when(wallets.adminCredit(
            any(), eq(customerId), eq(100001L), anyString(), anyString(), any(), anyString()))
        .thenThrow(new AppException("ADMIN_CREDIT_EXCEEDS_LIMIT", "cap", 422));
    assertThatThrownBy(
            () ->
                service.credit(
                    admin, customerId, new BigDecimal("1000.01"), "ADMIN_CREDIT", null, "note"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADMIN_CREDIT_EXCEEDS_LIMIT");
  }

  @Test
  void ac008_codRefundCredit_reasonRefund() {
    Map<String, Object> credited = new LinkedHashMap<>();
    credited.put("transaction_id", txId);
    credited.put("customer_id", customerId);
    credited.put("amount", new BigDecimal("100.00"));
    credited.put("new_balance", new BigDecimal("100.00"));
    credited.put("reason", "REFUND");
    credited.put("already_processed", false);
    when(wallets.systemCredit(
            eq(customerId),
            eq(10000L),
            eq("REFUND"),
            eq(orderId.toString()),
            anyString(),
            anyString()))
        .thenReturn(credited);

    Map<String, Object> result =
        service.credit(null, customerId, 100, "REFUND", orderId.toString(), "COD refund");

    assertThat(result.get("reason")).isEqualTo("REFUND");
    verify(ledger)
        .append(
            eq("WALLET_CREDIT"), eq(txId), eq("WALLET"), eq(10000L), eq(0L), anyString(), any());
  }

  @Test
  void credit_adminUsesAdminPort() {
    Map<String, Object> credited = new LinkedHashMap<>();
    credited.put("transaction_id", txId);
    credited.put("already_processed", false);
    when(wallets.adminCredit(
            eq(admin.subject()),
            eq(customerId),
            eq(7500L),
            eq("GOODWILL"),
            anyString(),
            isNull(),
            anyString()))
        .thenReturn(credited);

    Map<String, Object> result =
        service.credit(admin, customerId, 75, "ADMIN_CREDIT", null, "goodwill");
    assertThat(result.get("reason")).isEqualTo("ADMIN_CREDIT");
  }

  @Test
  void credit_replaySkipsLedger() {
    Map<String, Object> credited = new LinkedHashMap<>();
    credited.put("transaction_id", txId);
    credited.put("already_processed", true);
    when(wallets.systemCredit(any(), anyLong(), anyString(), any(), anyString(), anyString()))
        .thenReturn(credited);

    service.credit(null, customerId, 10, "REFUND", "ref-1", "n");
    verify(ledger, never())
        .append(anyString(), any(), anyString(), anyLong(), anyLong(), any(), any());
  }

  @Test
  void debit_requiresCustomerAndIdempotency() {
    assertThatThrownBy(() -> service.debit(null, 10, orderId, "k"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
    assertThatThrownBy(() -> service.debit(customerId, 10, orderId, "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void credit_invalidReasonAndNullCustomer() {
    assertThatThrownBy(() -> service.credit(null, null, 10, "REFUND", null, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
    assertThatThrownBy(() -> service.credit(null, customerId, 10, " ", null, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
  }

  @Test
  void balanceAndTransactions_delegate() {
    when(wallets.balance(customerId)).thenReturn(Map.of("balance", new BigDecimal("1.00")));
    when(wallets.transactions(customerId, 1, 20, "CREDIT"))
        .thenReturn(
            new CustomerWalletPort.TransactionsPage(List.of(Map.of("type", "CREDIT")), 1, 1, 20));
    assertThat(service.balance(customerId)).containsEntry("balance", new BigDecimal("1.00"));
    assertThat(service.transactions(customerId, 1, 20, "CREDIT").total()).isEqualTo(1);
  }

  @Test
  void debit_nullOrderStillLedgers() {
    when(wallets.debit(eq(customerId), isNull(), eq(100L), eq("k"), anyString()))
        .thenReturn(debitResult(false));
    service.debit(customerId, "1.00", null, "k");
    ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
    verify(ledger)
        .append(
            eq("WALLET_DEBIT"),
            eq(txId),
            eq("WALLET"),
            eq(0L),
            eq(100L),
            anyString(),
            meta.capture());
    assertThat(meta.getValue().get("order_id")).isEqualTo("");
  }

  @Test
  void credit_shapesMissingFieldsAndCashback() {
    Map<String, Object> credited = new LinkedHashMap<>();
    credited.put("transaction_id", txId.toString());
    credited.put("already_processed", false);
    // no note / reference_id / amount — façade fills them
    when(wallets.adminCredit(
            eq(admin.subject()),
            eq(customerId),
            eq(500L),
            eq("PROMOTIONAL"),
            anyString(),
            eq("ref-x"),
            anyString()))
        .thenReturn(credited);

    Map<String, Object> result =
        service.credit(admin, customerId, "5.00", "CASHBACK", "ref-x", "  ");
    assertThat(result.get("reason")).isEqualTo("CASHBACK");
    assertThat(result.get("note")).isEqualTo("Wallet credit");
    assertThat(result.get("reference_id")).isEqualTo("ref-x");
    assertThat(result.get("amount")).isEqualTo(new BigDecimal("5.00"));
  }

  @Test
  void credit_keepsExistingNoteAndReference() {
    Map<String, Object> credited = new LinkedHashMap<>();
    credited.put("transaction_id", txId);
    credited.put("already_processed", false);
    credited.put("note", "kept");
    credited.put("reference_id", "kept-ref");
    credited.put("amount", new BigDecimal("10.00"));
    when(wallets.systemCredit(any(), anyLong(), anyString(), any(), anyString(), anyString()))
        .thenReturn(credited);

    Map<String, Object> result =
        service.credit(null, customerId, 10, "REFUND", "ignored-when-present", "n");
    assertThat(result.get("note")).isEqualTo("kept");
    assertThat(result.get("reference_id")).isEqualTo("kept-ref");
  }

  @Test
  void credit_adminDefaultReasonAndNullTxId() {
    Map<String, Object> credited = new LinkedHashMap<>();
    credited.put("transaction_id", null);
    credited.put("already_processed", false);
    when(wallets.systemCredit(any(), anyLong(), eq("GOODWILL"), isNull(), anyString(), anyString()))
        .thenReturn(credited);

    // null transaction_id → asUuid generates a new id for ledger
    service.credit(null, customerId, 1, "GOODWILL", null, null);
    verify(ledger)
        .append(
            eq("WALLET_CREDIT"),
            any(UUID.class),
            eq("WALLET"),
            eq(100L),
            eq(0L),
            anyString(),
            any());

    Map<String, Object> adminCredited = new LinkedHashMap<>();
    adminCredited.put("transaction_id", txId);
    adminCredited.put("already_processed", false);
    when(wallets.adminCredit(
            eq(admin.subject()),
            eq(customerId),
            eq(100L),
            eq("GOODWILL"),
            anyString(),
            isNull(),
            anyString()))
        .thenReturn(adminCredited);
    service.credit(admin, customerId, 1, "GOODWILL", null, "n");
  }

  @Test
  void debit_nullIdempotencyKey() {
    assertThatThrownBy(() -> service.debit(customerId, 10, orderId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void credit_customerPrincipalUsesSystemPath() {
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    Map<String, Object> credited = new LinkedHashMap<>();
    credited.put("transaction_id", txId);
    credited.put("already_processed", false);
    when(wallets.systemCredit(any(), anyLong(), eq("REFUND"), eq("  "), anyString(), anyString()))
        .thenReturn(credited);

    // blank reference_id still builds a unique idempotency key
    service.credit(customer, customerId, 10, "REFUND", "  ", "n");
    verify(wallets)
        .systemCredit(eq(customerId), eq(1000L), eq("REFUND"), eq("  "), eq("n"), anyString());
  }

  @Test
  void credit_nullReason() {
    assertThatThrownBy(() -> service.credit(null, customerId, 10, null, null, "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REASON");
  }

  private Map<String, Object> debitResult(boolean already) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("transaction_id", txId);
    m.put("customer_id", customerId);
    m.put("deducted_amount", new BigDecimal("50.00"));
    m.put("balance_before", new BigDecimal("150.00"));
    m.put("remaining_balance", new BigDecimal("100.00"));
    m.put("idempotency_key", "key-1");
    m.put("already_processed", already);
    return m;
  }
}
