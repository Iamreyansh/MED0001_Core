package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.WalletService.TxPage;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.order.application.OrderPlacementService;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort;
import com.nammamedmate.payment.application.port.out.OrderPaymentStatusPort;
import com.nammamedmate.payment.application.port.out.WalletPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PaymentOrderBridgeConfigTest {

  @Test
  @SuppressWarnings("unchecked")
  void bridgesWalletLookupAndStatus() {
    PaymentOrderBridgeConfig config = new PaymentOrderBridgeConfig();
    WalletService wallets = mock(WalletService.class);
    when(wallets.debitForOrder(any(), any(), anyLong(), anyString()))
        .thenReturn(Map.of("amount_debited", new BigDecimal("12.50")));
    WalletPort wallet = config.paymentWalletPort(wallets);
    assertThat(wallet.debitForOrder(UUID.randomUUID(), UUID.randomUUID(), 1000, "x"))
        .isEqualTo(1250L);

    when(wallets.debitForOrder(any(), any(), anyLong(), anyString()))
        .thenReturn(Map.of("amount_debited", 5.0));
    assertThat(wallet.debitForOrder(UUID.randomUUID(), UUID.randomUUID(), 1000, "x"))
        .isEqualTo(500L);

    when(wallets.debitForOrder(any(), any(), anyLong(), anyString()))
        .thenReturn(Map.of("amount_debited", "x"));
    assertThat(wallet.debitForOrder(UUID.randomUUID(), UUID.randomUUID(), 1000, "x")).isZero();

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID orderId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(orderId)))
        .thenReturn(
            List.of(
                new OrderLookupPort.OrderSnapshot(
                    orderId, customerId, "UPI", 100, 0, "PAYMENT_PENDING")));
    OrderLookupPort lookup = config.jdbcOrderLookupPort(jdbc);
    assertThat(lookup.findById(orderId)).isPresent();

    OrderPlacementService placement = mock(OrderPlacementService.class);
    OrderPaymentStatusPort status = config.orderPaymentStatusPort(placement);
    status.onCaptured(orderId, "pay_1");
    status.onFailed(orderId, "declined");
    verify(placement).applyExternalPaymentCapture(orderId, "pay_1");
    verify(placement).applyExternalPaymentFailure(orderId, "declined");
  }

  @Test
  void bridgesCustomerWalletPort() {
    PaymentOrderBridgeConfig config = new PaymentOrderBridgeConfig();
    WalletService wallets = mock(WalletService.class);
    UUID customerId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    when(wallets.debitStrict(eq(customerId), eq(orderId), eq(100L), eq("k"), eq("n")))
        .thenReturn(Map.of("already_processed", false));
    when(wallets.systemCredit(
            eq(customerId), eq(200L), eq("note"), eq("ref"), eq("idem"), eq("REFUND")))
        .thenReturn(Map.of("reason", "REFUND"));
    when(wallets.adminCredit(any(), eq(customerId), any()))
        .thenReturn(Map.of("reason", "GOODWILL"));
    when(wallets.getBalanceForCustomer(customerId)).thenReturn(Map.of("balance", BigDecimal.ZERO));
    when(wallets.listTransactionsForCustomer(customerId, 1, 20, null))
        .thenReturn(new TxPage(List.of(), PaginationMeta.of(1, 20, 0)));

    CustomerWalletPort port = config.customerWalletPort(wallets);
    assertThat(port.debit(customerId, orderId, 100L, "k", "n"))
        .containsEntry("already_processed", false);
    assertThat(port.systemCredit(customerId, 200L, "REFUND", "ref", "note", "idem"))
        .containsEntry("reason", "REFUND");
    assertThat(port.adminCredit(adminId, customerId, 100L, "GOODWILL", "n", null, "idem"))
        .containsEntry("reason", "GOODWILL");
    assertThat(port.balance(customerId)).containsKey("balance");
    assertThat(port.transactions(customerId, 1, 20, null).total()).isZero();
  }
}
