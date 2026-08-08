package com.nammamedmate.api.config;

import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.port.out.ActiveOrdersPort;
import com.nammamedmate.customer.application.port.out.AddressInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.CustomerOrderHistoryPort;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridges: order WalletPort → customer WalletService; ActiveOrdersPort /
 * AddressInActiveOrderPort / CustomerOrderHistoryPort / OrderDemandPort / PharmacyOrderMetricsPort
 * → order JDBC.
 */
@Configuration
public class OrderCustomerBridgeConfig {

  @Bean
  @Primary
  WalletPort orderWalletPort(WalletService walletService) {
    return new WalletPort() {
      @Override
      public long debitForOrder(
          UUID customerId, UUID orderId, long orderTotalPaise, String description) {
        Map<String, Object> result =
            walletService.debitForOrder(customerId, orderId, orderTotalPaise, description);
        Object debited = result.get("amount_debited");
        if (debited instanceof BigDecimal bd) {
          return bd.movePointRight(2).longValueExact();
        }
        if (debited instanceof Number n) {
          return BigDecimal.valueOf(n.doubleValue()).movePointRight(2).longValue();
        }
        return 0L;
      }

      @Override
      public UUID creditForRefund(
          UUID customerId,
          UUID orderId,
          long amountPaise,
          String description,
          String idempotencyKey) {
        Map<String, Object> result =
            walletService.systemCredit(
                customerId,
                amountPaise,
                description,
                orderId == null ? null : orderId.toString(),
                idempotencyKey);
        Object tx = result.get("transaction_id");
        if (tx instanceof UUID u) {
          return u;
        }
        if (tx != null) {
          return UUID.fromString(tx.toString());
        }
        return null;
      }
    };
  }

  @Bean
  @Primary
  ActiveOrdersPort orderActiveOrdersPort(OrderStore orders) {
    return orders::hasActiveOrders;
  }

  @Bean
  @Primary
  AddressInActiveOrderPort orderAddressInActiveOrderPort(OrderStore orders) {
    return orders::isAddressInActiveOrder;
  }

  @Bean
  @Primary
  CustomerOrderHistoryPort jdbcCustomerOrderHistoryPort(OrderStore orders) {
    return orders::hasPlacedAnyOrder;
  }

  @Bean
  @Primary
  OrderDemandPort jdbcOrderDemandPort(JdbcTemplate jdbc) {
    return new JdbcOrderDemandBridge(jdbc);
  }

  @Bean
  @Primary
  PharmacyOrderMetricsPort jdbcPharmacyOrderMetricsPort(JdbcTemplate jdbc) {
    return new JdbcPharmacyOrderMetricsBridge(jdbc);
  }
}
