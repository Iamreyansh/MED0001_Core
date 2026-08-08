package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderCustomerBridgeConfigTest {

  @Test
  void walletAndActiveOrderBridges() {
    OrderCustomerBridgeConfig config = new OrderCustomerBridgeConfig();
    WalletService wallets = mock(WalletService.class);
    OrderStore orders = mock(OrderStore.class);
    UUID customerId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    WalletPort port = config.orderWalletPort(wallets);

    when(wallets.debitForOrder(eq(customerId), eq(orderId), eq(10_000L), any()))
        .thenReturn(Map.of("amount_debited", new BigDecimal("50.00")));
    assertThat(port.debitForOrder(customerId, orderId, 10_000L, "x")).isEqualTo(5_000L);

    when(wallets.debitForOrder(eq(customerId), isNull(), anyLong(), any()))
        .thenReturn(Map.of("amount_debited", 12.5));
    assertThat(port.debitForOrder(customerId, null, 100, "x")).isEqualTo(1250L);

    when(wallets.debitForOrder(any(), any(), anyLong(), any()))
        .thenReturn(Map.of("amount_debited", "nope"));
    assertThat(port.debitForOrder(customerId, orderId, 1, "x")).isEqualTo(0L);

    UUID txId = UUID.randomUUID();
    when(wallets.systemCredit(eq(customerId), eq(5000L), any(), eq(orderId.toString()), eq("ik")))
        .thenReturn(Map.of("transaction_id", txId));
    assertThat(port.creditForRefund(customerId, orderId, 5000L, "refund", "ik")).isEqualTo(txId);

    when(wallets.systemCredit(eq(customerId), eq(100L), any(), isNull(), eq("ik2")))
        .thenReturn(Map.of("transaction_id", txId.toString()));
    assertThat(port.creditForRefund(customerId, null, 100L, "refund", "ik2")).isEqualTo(txId);

    when(wallets.systemCredit(any(), anyLong(), any(), any(), any()))
        .thenReturn(new java.util.HashMap<>());
    assertThat(port.creditForRefund(customerId, orderId, 1L, "x", "ik3")).isNull();

    when(orders.hasActiveOrders(customerId)).thenReturn(true);
    assertThat(config.orderActiveOrdersPort(orders).hasActiveOrders(customerId)).isTrue();
    when(orders.isAddressInActiveOrder(orderId)).thenReturn(true);
    assertThat(config.orderAddressInActiveOrderPort(orders).isAddressInActiveOrder(orderId))
        .isTrue();
    when(orders.hasPlacedAnyOrder(customerId)).thenReturn(true);
    assertThat(config.jdbcCustomerOrderHistoryPort(orders).hasPlacedAnyOrder(customerId)).isTrue();

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderDemandPort demand = config.jdbcOrderDemandPort(jdbc);
    assertThat(demand).isInstanceOf(JdbcOrderDemandBridge.class);
    PharmacyOrderMetricsPort metrics = config.jdbcPharmacyOrderMetricsPort(jdbc);
    assertThat(metrics).isInstanceOf(JdbcPharmacyOrderMetricsBridge.class);
  }
}
