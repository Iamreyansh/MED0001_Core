package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class JdbcOrderStoreFinalCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void coversPhoneMapperExtractorsParseCatchAndToJsonCatch() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper om = mock(ObjectMapper.class);
    when(om.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    JdbcOrderStore store = new JdbcOrderStore(jdbc, om);

    when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
    Instant t0 = Instant.parse("2026-08-08T06:00:00Z");
    UUID id = UUID.randomUUID();
    store.insert(
        new Order(
            id,
            "ORD",
            id,
            id,
            id,
            List.of(new OrderItemSnapshot(id, "A", 1, 1, 1, false)),
            1,
            null,
            0,
            0,
            0,
            0,
            1,
            PaymentMethod.COD,
            PaymentStatus.PAID,
            null,
            null,
            null,
            id,
            null,
            OrderStatus.PENDING_ACCEPTANCE,
            null,
            null,
            null,
            null,
            null,
            t0,
            t0));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("phone")).thenReturn("   ");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findPharmacyPhone(id)).isEmpty();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(UUID.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getBoolean(1)).thenReturn(true);
              return ex.extractData(rs);
            });
    assertThat(store.hasActiveOrders(id)).isTrue();
    assertThat(store.hasPlacedAnyOrder(id)).isTrue();
    assertThat(store.isAddressInActiveOrder(id)).isTrue();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(LocalDate.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    try {
      store.nextSequence(LocalDate.of(2026, 8, 8));
    } catch (IllegalStateException ignored) {
      // expected
    }

    ObjectMapper real = new ObjectMapper();
    JdbcOrderStore realStore = new JdbcOrderStore(jdbc, real);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Order> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("order_number")).thenReturn("ORD");
              when(rs.getObject("customer_id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getObject("cart_id")).thenReturn(id);
              when(rs.getString("items")).thenReturn("{bad");
              when(rs.getLong("item_total_paise")).thenReturn(0L);
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
              when(rs.getLong("handling_fee_paise")).thenReturn(0L);
              when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
              when(rs.getLong("total_payable_paise")).thenReturn(0L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("gateway_order_id")).thenReturn(null);
              when(rs.getString("gateway_payment_id")).thenReturn(null);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(id);
              when(rs.getString("delivery_instructions")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(null);
              when(rs.getString("placement_idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(realStore.findById(id).orElseThrow().items()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Order> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("order_number")).thenReturn("ORD");
              when(rs.getObject("customer_id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getObject("cart_id")).thenReturn(id);
              when(rs.getString("items"))
                  .thenReturn(
                      "[{\"product_id\":null,\"name\":null,\"quantity\":1,\"unit_price_paise\":1,\"line_total_paise\":1,\"rx_required\":false}]");
              when(rs.getLong("item_total_paise")).thenReturn(1L);
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
              when(rs.getLong("handling_fee_paise")).thenReturn(0L);
              when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
              when(rs.getLong("total_payable_paise")).thenReturn(1L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("gateway_order_id")).thenReturn(null);
              when(rs.getString("gateway_payment_id")).thenReturn(null);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(id);
              when(rs.getString("delivery_instructions")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(null);
              when(rs.getString("placement_idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(realStore.findById(id).orElseThrow().items().getFirst().productId()).isNull();

    assertThat(realStore.findByPlacementIdempotencyKey(null)).isEmpty();
    assertThat(realStore.findByGatewayOrderId(null)).isEmpty();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(UUID.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.hasActiveOrders(id)).isFalse();
    assertThat(store.hasPlacedAnyOrder(id)).isFalse();
    assertThat(store.isAddressInActiveOrder(id)).isFalse();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("phone")).thenReturn(null);
              java.util.ArrayList<String> rows = new java.util.ArrayList<>();
              rows.add(mapper.mapRow(rs, 0));
              return rows;
            });
    assertThat(store.findPharmacyPhone(id)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("phone")).thenReturn("999");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findPharmacyPhone(id)).contains("999");

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Order> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("order_number")).thenReturn("ORD");
              when(rs.getObject("customer_id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getObject("cart_id")).thenReturn(id);
              when(rs.getString("items"))
                  .thenReturn(
                      "[{\"product_id\":\""
                          + id
                          + "\",\"name\":\"A\",\"quantity\":1,\"unit_price_paise\":1,\"line_total_paise\":1,\"rx_required\":\"true\"}]");
              when(rs.getLong("item_total_paise")).thenReturn(1L);
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
              when(rs.getLong("handling_fee_paise")).thenReturn(0L);
              when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
              when(rs.getLong("total_payable_paise")).thenReturn(1L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("gateway_order_id")).thenReturn(null);
              when(rs.getString("gateway_payment_id")).thenReturn(null);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(id);
              when(rs.getString("delivery_instructions")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(null);
              when(rs.getString("placement_idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    Order parsed = realStore.findById(id).orElseThrow();
    assertThat(parsed.items().getFirst().productId()).isEqualTo(id);
    assertThat(parsed.items().getFirst().rxRequired()).isTrue();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Order> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("order_number")).thenReturn("ORD");
              when(rs.getObject("customer_id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getObject("cart_id")).thenReturn(id);
              when(rs.getString("items")).thenReturn("   ");
              when(rs.getLong("item_total_paise")).thenReturn(0L);
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
              when(rs.getLong("handling_fee_paise")).thenReturn(0L);
              when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
              when(rs.getLong("total_payable_paise")).thenReturn(0L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("gateway_order_id")).thenReturn(null);
              when(rs.getString("gateway_payment_id")).thenReturn(null);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(id);
              when(rs.getString("delivery_instructions")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(null);
              when(rs.getString("placement_idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(realStore.findById(id).orElseThrow().items()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Order> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("order_number")).thenReturn("ORD");
              when(rs.getObject("customer_id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getObject("cart_id")).thenReturn(id);
              when(rs.getString("items")).thenReturn(null);
              when(rs.getLong("item_total_paise")).thenReturn(0L);
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
              when(rs.getLong("handling_fee_paise")).thenReturn(0L);
              when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
              when(rs.getLong("total_payable_paise")).thenReturn(0L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("gateway_order_id")).thenReturn(null);
              when(rs.getString("gateway_payment_id")).thenReturn(null);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(id);
              when(rs.getString("delivery_instructions")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(null);
              when(rs.getString("placement_idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(realStore.findById(id).orElseThrow().items()).isEmpty();
  }
}
