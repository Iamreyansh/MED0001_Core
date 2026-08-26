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
import org.junit.jupiter.api.BeforeEach;
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
class JdbcOrderStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcOrderStore store;
  private final UUID id = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000010");
  private final UUID customerId = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final UUID pharmacyId = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private final UUID cartId = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
  private final UUID addressId = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private final UUID medId = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private final Instant t0 = Instant.parse("2026-08-08T06:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcOrderStore(jdbc, new ObjectMapper());
  }

  @Test
  void insertUpdateFindSequencePhoneAndGuards() throws Exception {
    when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
    Order order = sampleOrder();
    assertThat(store.insert(order)).isSameAs(order);

    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    order.confirm(t0, t0.plusSeconds(600), "pay_1");
    assertThat(store.update(order)).isSameAs(order);

    stubOrderQuery();
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByCustomerAndId(customerId, id)).isPresent();
    assertThat(store.findByPharmacyAndId(pharmacyId, id)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(Collections.emptyList());
    assertThat(store.findByPharmacyAndId(pharmacyId, UUID.randomUUID())).isEmpty();
    stubOrderQuery();
    assertThat(store.findByPlacementIdempotencyKey("idem-1")).isPresent();
    assertThat(store.findByGatewayOrderId("order_rz")).isPresent();
    assertThat(store.findByPlacementIdempotencyKey(" ")).isEmpty();
    assertThat(store.findByGatewayOrderId(null)).isEmpty();
    assertThat(store.findPendingAcceptanceTimedOut(t0, 10)).hasSize(1);
    assertThat(store.findReadyWithoutRiderEscalation(t0, 10)).hasSize(1);
    assertThat(store.findOpenPastSlaDeadline(t0, 10)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacyId)))
        .thenReturn(List.of("+91-80"));
    assertThat(store.findPharmacyPhone(pharmacyId)).contains("+91-80");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacyId))).thenReturn(List.of("  "));
    assertThat(store.findPharmacyPhone(pharmacyId)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacyId)))
        .thenReturn(Collections.emptyList());
    assertThat(store.findPharmacyPhone(pharmacyId)).isEmpty();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(LocalDate.class)))
        .thenReturn(7);
    assertThat(store.nextSequence(LocalDate.of(2026, 8, 8))).isEqualTo(7);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(LocalDate.class)))
        .thenReturn(null);
    assertThatThrownBy(() -> store.nextSequence(LocalDate.of(2026, 8, 8)))
        .isInstanceOf(IllegalStateException.class);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(UUID.class))).thenReturn(true);
    assertThat(store.hasActiveOrders(customerId)).isTrue();
    assertThat(store.hasPlacedAnyOrder(customerId)).isTrue();
    assertThat(store.isAddressInActiveOrder(addressId)).isTrue();

    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(0);
    assertThatThrownBy(() -> store.update(order)).isInstanceOf(IllegalStateException.class);
  }

  @SuppressWarnings("unchecked")
  private void stubOrderQuery() {
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("phone")) {
                return List.of("+91-80");
              }
              RowMapper<Order> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(orderRs(), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Order> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(orderRs(), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(Integer.class)))
        .thenAnswer(
            inv -> {
              RowMapper<Order> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(orderRs(), 0));
            });
  }

  private ResultSet orderRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("order_number")).thenReturn("ORD-20260808-00001");
    when(rs.getObject("customer_id")).thenReturn(customerId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
    when(rs.getObject("cart_id")).thenReturn(cartId);
    when(rs.getString("items"))
        .thenReturn(
            "[{\"product_id\":\""
                + medId
                + "\",\"name\":\"Metformin\",\"quantity\":1,\"unit_price_paise\":8500,\"line_total_paise\":8500,\"rx_required\":false}]");
    when(rs.getLong("item_total_paise")).thenReturn(8500L);
    when(rs.getString("coupon_code")).thenReturn(null);
    when(rs.getLong("coupon_discount_paise")).thenReturn(0L);
    when(rs.getLong("delivery_fee_paise")).thenReturn(2500L);
    when(rs.getLong("handling_fee_paise")).thenReturn(500L);
    when(rs.getLong("wallet_applied_paise")).thenReturn(0L);
    when(rs.getLong("total_payable_paise")).thenReturn(11500L);
    when(rs.getString("payment_method")).thenReturn("COD");
    when(rs.getString("payment_status")).thenReturn("PENDING_COLLECTION");
    when(rs.getString("gateway_order_id")).thenReturn("order_rz");
    when(rs.getString("gateway_payment_id")).thenReturn(null);
    when(rs.getObject("prescription_id")).thenReturn(null);
    when(rs.getObject("delivery_address_id")).thenReturn(addressId);
    when(rs.getString("delivery_instructions")).thenReturn(null);
    when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
    when(rs.getObject("rider_id")).thenReturn(null);
    when(rs.getString("delivery_otp_hash")).thenReturn(null);
    when(rs.getString("placement_idempotency_key")).thenReturn("idem-1");
    when(rs.getTimestamp("confirmed_at")).thenReturn(Timestamp.from(t0));
    when(rs.getTimestamp("estimated_delivery_at")).thenReturn(Timestamp.from(t0.plusSeconds(600)));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t0));
    when(rs.getTimestamp("accepted_at")).thenReturn(null);
    when(rs.getTimestamp("delivered_at")).thenReturn(null);
    when(rs.getTimestamp("sla_deadline")).thenReturn(Timestamp.from(t0.plusSeconds(1800)));
    when(rs.getBoolean("sla_breached")).thenReturn(false);
    when(rs.getTimestamp("rider_assigned_at")).thenReturn(null);
    when(rs.getTimestamp("otp_verified_at")).thenReturn(null);
    when(rs.getTimestamp("ready_for_pickup_at")).thenReturn(null);
    when(rs.getTimestamp("rider_escalation_at")).thenReturn(null);
    when(rs.getString("cancel_reason")).thenReturn(null);
    return rs;
  }

  private Order sampleOrder() {
    return new Order(
        id,
        "ORD-20260808-00001",
        customerId,
        pharmacyId,
        cartId,
        List.of(new OrderItemSnapshot(medId, "Metformin", 1, 8500, 8500, false)),
        8500,
        null,
        0,
        2500,
        500,
        0,
        11500,
        PaymentMethod.COD,
        PaymentStatus.PENDING_COLLECTION,
        "order_rz",
        null,
        null,
        addressId,
        null,
        OrderStatus.PENDING_ACCEPTANCE,
        null,
        null,
        "idem-1",
        t0,
        t0.plusSeconds(600),
        t0,
        t0);
  }
}
