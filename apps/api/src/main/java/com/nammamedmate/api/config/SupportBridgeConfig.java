package com.nammamedmate.api.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.RefundService;
import com.nammamedmate.order.application.port.out.ExternalDisputeBannerPort;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.application.port.out.RefundPort;
import com.nammamedmate.support.application.port.out.SupportDisputeBannerPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition-root bridges for EPIC-015 support → customers/orders/payment JDBC. */
@Configuration
public class SupportBridgeConfig {

  @Bean
  @Primary
  CustomerLookupPort jdbcSupportCustomerLookupPort(JdbcTemplate jdbc) {
    return new CustomerLookupPort() {
      @Override
      public Optional<CustomerContext> find(UUID customerId) {
        if (customerId == null) {
          return Optional.empty();
        }
        var names =
            jdbc.query(
                """
                SELECT COALESCE(name, 'Customer') AS name
                FROM customers
                WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> rs.getString("name"),
                customerId);
        if (names.isEmpty()) {
          return Optional.empty();
        }
        Long orders =
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM orders
                WHERE customer_id = ? AND deleted_at IS NULL
                """,
                Long.class,
                customerId);
        Long ltvPaise =
            jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(total_payable_paise), 0) FROM orders
                WHERE customer_id = ? AND deleted_at IS NULL AND status = 'DELIVERED'
                """,
                Long.class,
                customerId);
        long ltvRs = ltvPaise == null ? 0L : ltvPaise / 100;
        return Optional.of(
            new CustomerContext(customerId, names.getFirst(), orders == null ? 0 : orders, ltvRs));
      }

      @Override
      public Optional<String> displayName(UUID customerId) {
        return find(customerId).map(CustomerContext::customerName);
      }
    };
  }

  @Bean
  @Primary
  OrderContextPort jdbcSupportOrderContextPort(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return orderId -> {
      if (orderId == null) {
        return Optional.empty();
      }
      var rows =
          jdbc.query(
              """
              SELECT o.id, o.customer_id, o.status, o.total_payable_paise, o.items::text AS items,
                     COALESCE(p.name, 'Pharmacy') AS pharmacy_name,
                     r.name AS rider_name
              FROM orders o
              LEFT JOIN pharmacies p ON p.id = o.pharmacy_id
              LEFT JOIN riders r ON r.id = o.rider_id
              WHERE o.id = ? AND o.deleted_at IS NULL
              """,
              (rs, i) -> {
                List<OrderContextPort.OrderItem> items =
                    parseItems(objectMapper, rs.getString("items"));
                String rider = rs.getString("rider_name");
                return new OrderContextPort.OrderContext(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("status"),
                    rs.getLong("total_payable_paise"),
                    items,
                    rs.getString("pharmacy_name"),
                    rider,
                    "https://tracking.nammamedmate.com/" + orderId);
              },
              orderId);
      return rows.stream().findFirst();
    };
  }

  @Bean
  @Primary
  RefundPort supportRefundPort(RefundService refunds, OrderStore orders) {
    return (orderId, customerId, amountPaise, refundTo, disputeId) -> {
      Order order =
          orders
              .findById(orderId)
              .orElseThrow(() -> new IllegalStateException("Order not found for support refund"));
      var plan = refunds.initiate(order, "SUPPORT_DISPUTE", ActorType.ADMIN, null);
      String txn = plan.initiated() ? "refund-" + orderId : "none-" + orderId;
      return new RefundPort.RefundResult(txn, plan.initiated());
    };
  }

  @Bean
  @Primary
  ExternalDisputeBannerPort jdbcExternalDisputeBannerPort(SupportDisputeBannerPort banners) {
    return orderId ->
        banners
            .findForOrder(orderId)
            .map(
                b -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("dispute_id", b.disputeId());
                  m.put("status", b.status());
                  m.put("dispute_type", b.disputeType());
                  m.put("liable_party", b.liableParty());
                  m.put("reason", b.description());
                  m.put("flagged_at", b.createdAt() == null ? null : b.createdAt().toString());
                  return m;
                });
  }

  private static List<OrderContextPort.OrderItem> parseItems(ObjectMapper om, String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> raw = om.readValue(json, new TypeReference<>() {});
      List<OrderContextPort.OrderItem> out = new ArrayList<>();
      for (Map<String, Object> m : raw) {
        String name = m.get("name") == null ? "Item" : m.get("name").toString();
        int qty = m.get("quantity") == null ? 0 : ((Number) m.get("quantity")).intValue();
        long price =
            m.get("unit_price_paise") == null
                ? 0L
                : ((Number) m.get("unit_price_paise")).longValue();
        out.add(new OrderContextPort.OrderItem(name, qty, price));
      }
      return out;
    } catch (JsonProcessingException | RuntimeException e) {
      return List.of();
    }
  }
}
