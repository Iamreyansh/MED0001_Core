package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.RefundService;
import com.nammamedmate.order.application.port.out.ExternalDisputeBannerPort;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.RefundInitiatorPort;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.application.port.out.RefundPort;
import com.nammamedmate.support.application.port.out.SupportDisputeBannerPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SupportBridgeConfigTest {

  @Test
  @SuppressWarnings("unchecked")
  void jdbcLookupReturnsContext() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID customerId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(customerId)))
        .thenReturn(List.of("Priya Sharma"));
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(customerId))).thenReturn(24L, 840000L);
    CustomerLookupPort port = new SupportBridgeConfig().jdbcSupportCustomerLookupPort(jdbc);
    assertThat(port.find(customerId)).isPresent();
    assertThat(port.find(customerId).orElseThrow().ltvRs()).isEqualTo(8400);
    assertThat(port.displayName(customerId)).contains("Priya Sharma");
    assertThat(port.find(null)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void orderContextAndBannerAndRefund() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper om = new ObjectMapper();
    UUID orderId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(orderId)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              var rs = mock(java.sql.ResultSet.class);
              when(rs.getObject("id")).thenReturn(orderId);
              when(rs.getObject("customer_id")).thenReturn(customerId);
              when(rs.getString("status")).thenReturn("DELIVERED");
              when(rs.getLong("total_payable_paise")).thenReturn(9600L);
              when(rs.getString("items"))
                  .thenReturn(
                      "[{\"name\":\"Paracetamol\",\"quantity\":2,\"unit_price_paise\":4800}]");
              when(rs.getString("pharmacy_name")).thenReturn("Apollo");
              when(rs.getString("rider_name")).thenReturn("Kiran");
              return List.of(mapper.mapRow(rs, 0));
            });
    OrderContextPort orders = new SupportBridgeConfig().jdbcSupportOrderContextPort(jdbc, om);
    assertThat(orders.find(orderId)).isPresent();
    assertThat(orders.find(null)).isEmpty();
    assertThat(orders.find(orderId).orElseThrow().items()).hasSize(1);

    SupportDisputeBannerPort banners =
        oid ->
            Optional.of(
                new SupportDisputeBannerPort.Banner(
                    "DSP-1", "OPEN", "WRONG_ITEMS", null, "wrong", Instant.now()));
    ExternalDisputeBannerPort ext =
        new SupportBridgeConfig().jdbcExternalDisputeBannerPort(banners);
    assertThat(ext.findBanner(orderId)).isPresent();
    assertThat(ext.findBanner(orderId).orElseThrow().get("dispute_id")).isEqualTo("DSP-1");

    RefundService refunds = mock(RefundService.class);
    OrderStore orderStore = mock(OrderStore.class);
    Order order = mock(Order.class);
    when(orderStore.findById(orderId)).thenReturn(Optional.of(order));
    when(refunds.initiate(any(), anyString(), any(), any()))
        .thenReturn(new RefundInitiatorPort.RefundPlan(true, 9600, "SOURCE"));
    RefundPort port = new SupportBridgeConfig().supportRefundPort(refunds, orderStore);
    assertThat(
            port.processRefund(orderId, customerId, 9600, "SOURCE", UUID.randomUUID())
                .transactionId())
        .startsWith("refund-");
  }
}
