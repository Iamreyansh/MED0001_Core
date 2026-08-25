package com.nammamedmate.api.config;

import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.port.out.ActiveOrdersPort;
import com.nammamedmate.customer.application.port.out.AddressInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.CustomerOrderHistoryPort;
import com.nammamedmate.customer.application.port.out.PaymentMethodInActiveOrderPort;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
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
  WalletPort orderWalletPort(WalletService walletService, FinancialLedgerWriterPort ledger) {
    return new WalletPort() {
      @Override
      public long debitForOrder(
          UUID customerId, UUID orderId, long orderTotalPaise, String description) {
        Map<String, Object> result =
            walletService.debitForOrder(customerId, orderId, orderTotalPaise, description);
        long debitPaise = 0L;
        Object debited = result.get("amount_debited");
        if (debited instanceof BigDecimal bd) {
          debitPaise = bd.movePointRight(2).longValueExact();
        } else if (debited instanceof Number n) {
          debitPaise = BigDecimal.valueOf(n.doubleValue()).movePointRight(2).longValue();
        }
        if (debitPaise > 0
            && ledger != null
            && !Boolean.TRUE.equals(result.get("already_processed"))) {
          Object tx = result.get("transaction_id");
          UUID txId =
              tx instanceof UUID u
                  ? u
                  : tx != null ? UUID.fromString(tx.toString()) : UUID.randomUUID();
          String note =
              description == null || description.isBlank()
                  ? "Auto-applied at checkout"
                  : description;
          ledger.append(
              "WALLET_DEBIT",
              txId,
              "WALLET",
              0L,
              debitPaise,
              note,
              Map.of(
                  "customer_id",
                  customerId == null ? "" : customerId.toString(),
                  "order_id",
                  orderId == null ? "" : orderId.toString()));
        }
        return debitPaise;
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
                idempotencyKey,
                "REFUND");
        Object tx = result.get("transaction_id");
        UUID txId = tx instanceof UUID u ? u : tx != null ? UUID.fromString(tx.toString()) : null;
        if (txId != null
            && amountPaise > 0
            && ledger != null
            && !Boolean.TRUE.equals(result.get("already_processed"))) {
          String note =
              description == null || description.isBlank() ? "Wallet refund" : description;
          ledger.append(
              "WALLET_CREDIT",
              txId,
              "WALLET",
              amountPaise,
              0L,
              note,
              Map.of(
                  "customer_id",
                  customerId == null ? "" : customerId.toString(),
                  "order_id",
                  orderId == null ? "" : orderId.toString()));
        }
        return txId;
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
  PaymentMethodInActiveOrderPort orderPaymentMethodInActiveOrderPort(JdbcTemplate jdbc) {
    return methodId ->
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                  SELECT 1 FROM orders o
                  WHERE o.saved_payment_method_id = ?
                    AND o.deleted_at IS NULL
                    AND o.status NOT IN ('DELIVERED','CANCELLED')
                )
                """,
                Boolean.class,
                methodId));
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
