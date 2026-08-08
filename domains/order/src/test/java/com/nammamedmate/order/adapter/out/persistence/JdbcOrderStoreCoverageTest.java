package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcOrderStoreCoverageTest {

  @Mock private JdbcTemplate jdbc;
  private final Instant t0 = Instant.parse("2026-08-08T06:00:00Z");
  private final UUID id = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000099");

  @Test
  void emptyFindersFalseExistsBlankPhoneAndItemVariants() throws Exception {
    JdbcOrderStore store = new JdbcOrderStore(jdbc, new ObjectMapper());

    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());
    assertThat(store.findById(id)).isEmpty();
    assertThat(store.findByCustomerAndId(id, id)).isEmpty();
    assertThat(store.findByPlacementIdempotencyKey("x")).isEmpty();
    assertThat(store.findByRazorpayOrderId("x")).isEmpty();
    assertThat(store.findByPlacementIdempotencyKey("")).isEmpty();
    assertThat(store.findByRazorpayOrderId("  ")).isEmpty();

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

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(UUID.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getBoolean(1)).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.hasActiveOrders(id)).isFalse();
    assertThat(store.hasPlacedAnyOrder(id)).isFalse();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(LocalDate.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getInt(1)).thenReturn(9);
              return ex.extractData(rs);
            });
    assertThat(store.nextSequence(LocalDate.of(2026, 8, 8))).isEqualTo(9);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenReturn(Collections.singletonList(null));
    assertThat(store.findPharmacyPhone(id)).isEmpty();

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
              when(rs.getString("items")).thenReturn("[]");
              when(rs.getLong("item_total_paise")).thenReturn(0L);
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
              when(rs.getLong("handling_fee_paise")).thenReturn(0L);
              when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
              when(rs.getLong("total_payable_paise")).thenReturn(0L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(id);
              when(rs.getString("delivery_instructions")).thenReturn(null);
              when(rs.getString("status")).thenReturn("DELIVERED");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(null);
              when(rs.getString("placement_idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    // number/string coercion in items
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
                          + "\",\"name\":\"A\",\"quantity\":2,\"unit_price_paise\":10,\"line_total_paise\":20,\"rx_required\":\"true\"}]");
              when(rs.getLong("item_total_paise")).thenReturn(20L);
              when(rs.getString("coupon_code")).thenReturn(null);
              when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
              when(rs.getLong("delivery_fee_paise")).thenReturn(0L);
              when(rs.getLong("handling_fee_paise")).thenReturn(0L);
              when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
              when(rs.getLong("total_payable_paise")).thenReturn(20L);
              when(rs.getString("payment_method")).thenReturn("UPI");
              when(rs.getString("payment_status")).thenReturn("PAID");
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
              when(rs.getObject("prescription_id")).thenReturn(null);
              when(rs.getObject("delivery_address_id")).thenReturn(id);
              when(rs.getString("delivery_instructions")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(null);
              when(rs.getString("placement_idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    Order mapped = store.findById(id).orElseThrow();
    assertThat(mapped.items()).hasSize(1);

    // blank items json
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
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
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
    assertThat(store.findById(id).orElseThrow().items()).isEmpty();

    when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
    Order sample =
        new Order(
            id,
            "ORD-1",
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
            t0);
    store.insert(sample);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(LocalDate.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<Integer> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThatThrownBy(() -> store.nextSequence(LocalDate.of(2026, 8, 8)))
        .isInstanceOf(IllegalStateException.class);
  }
}
