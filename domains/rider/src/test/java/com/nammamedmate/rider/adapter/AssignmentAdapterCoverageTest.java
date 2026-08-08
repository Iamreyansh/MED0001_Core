package com.nammamedmate.rider.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.adapter.in.web.AdminDispatchController;
import com.nammamedmate.rider.adapter.in.web.RiderOrderController;
import com.nammamedmate.rider.adapter.out.cache.RedisAssignmentOtpCache;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDispatchOrderAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcOrderAssignmentStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderTripEarningsStore;
import com.nammamedmate.rider.application.DispatchService;
import com.nammamedmate.rider.application.DispatchService.QueueResult;
import com.nammamedmate.rider.application.RiderOrderService;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.EarningsRecord;
import com.nammamedmate.rider.domain.AssignmentOtps;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AssignmentAdapterCoverageTest {

  @Test
  void controllersDelegate() {
    DispatchService dispatch = mock(DispatchService.class);
    RiderOrderService riderOrders = mock(RiderOrderService.class);
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MedmatePrincipal rider =
        new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j");
    UUID orderId = Ids.newId();
    when(dispatch.queue(any(), any(), any(), any()))
        .thenReturn(new QueueResult(Map.of("queue", List.of()), PaginationMeta.of(1, 20, 0)));
    when(dispatch.assignManual(any(), any(), any())).thenReturn(Map.of("ok", true));
    when(dispatch.autoAssignAll(any())).thenReturn(Map.of("assigned", 0));
    when(dispatch.reassign(any(), any(), any(), any())).thenReturn(Map.of("ok", true));
    when(riderOrders.current(any())).thenReturn(Map.of("order_id", orderId.toString()));
    when(riderOrders.accept(any(), any())).thenReturn(Map.of("assignment_status", "ACCEPTED"));
    when(riderOrders.pickupConfirm(any(), any(), any()))
        .thenReturn(Map.of("order_status", "OUT_FOR_DELIVERY"));
    when(riderOrders.deliver(any(), any(), any())).thenReturn(Map.of("order_status", "DELIVERED"));

    AdminDispatchController adminCtrl = new AdminDispatchController(dispatch);
    ApiResponse<Map<String, Object>> q = adminCtrl.queue(admin, null, 1, 20);
    assertThat(q.success()).isTrue();
    adminCtrl.assign(admin, orderId, new AdminDispatchController.AssignRequest(Ids.newId()));
    adminCtrl.autoAssignAll(admin);
    adminCtrl.reassign(
        admin, orderId, new AdminDispatchController.ReassignRequest(Ids.newId(), "OTHER"));

    RiderOrderController riderCtrl = new RiderOrderController(riderOrders);
    assertThat(riderCtrl.current(rider).data()).containsKey("order_id");
    riderCtrl.accept(rider, orderId);
    riderCtrl.pickupConfirm(rider, orderId, new RiderOrderController.PickupRequest("1234"));
    riderCtrl.deliver(rider, orderId, new RiderOrderController.DeliverRequest("5678"));
    riderCtrl.pickupConfirm(rider, orderId, null);
    riderCtrl.deliver(rider, orderId, null);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcAssignmentAndEarningsStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    Instant now = Instant.parse("2026-07-24T09:00:00Z");
    UUID id = Ids.newId();
    UUID orderId = Ids.newId();
    UUID riderId = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("order_id")).thenReturn(orderId);
              when(rs.getObject("rider_id")).thenReturn(riderId);
              when(rs.getString("assignment_type")).thenReturn("MANUAL");
              when(rs.getObject("assigned_by")).thenReturn(Ids.newId());
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getTimestamp("accept_deadline")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("accepted_at")).thenReturn(null);
              when(rs.getTimestamp("pickup_confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getString("pickup_otp_hash")).thenReturn("abc");
              when(rs.getString("delivery_otp_hash")).thenReturn("def");
              when(rs.getString("reassign_reason")).thenReturn(null);
              when(rs.getBigDecimal("composite_score")).thenReturn(BigDecimal.TEN);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("order_id")).thenReturn(orderId);
              when(rs.getObject("rider_id")).thenReturn(riderId);
              when(rs.getString("assignment_type")).thenReturn("AUTO");
              when(rs.getObject("assigned_by")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getTimestamp("accept_deadline")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("accepted_at")).thenReturn(null);
              when(rs.getTimestamp("pickup_confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getString("pickup_otp_hash")).thenReturn("abc");
              when(rs.getString("delivery_otp_hash")).thenReturn("def");
              when(rs.getString("reassign_reason")).thenReturn(null);
              when(rs.getBigDecimal("composite_score")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });

    JdbcOrderAssignmentStore store = new JdbcOrderAssignmentStore(jdbc);
    AssignmentRecord row =
        new AssignmentRecord(
            id,
            orderId,
            riderId,
            "MANUAL",
            Ids.newId(),
            "PENDING_ACCEPTANCE",
            now.plusSeconds(300),
            null,
            null,
            null,
            "h1",
            "h2",
            null,
            BigDecimal.ONE,
            now,
            now);
    store.insert(row);
    store.update(row);
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findActiveByOrder(orderId)).isPresent();
    assertThat(store.findCurrentForRider(riderId)).isPresent();
    assertThat(store.countActiveForRider(riderId)).isEqualTo(1);
    assertThat(store.hasActiveForOrder(orderId)).isTrue();
    assertThat(store.findPendingPastDeadline(now, 10)).hasSize(1);

    JdbcRiderTripEarningsStore earnings = new JdbcRiderTripEarningsStore(jdbc);
    earnings.insert(
        new EarningsRecord(
            Ids.newId(),
            riderId,
            orderId,
            id,
            java.time.LocalDate.of(2026, 7, 24),
            2500,
            0,
            0,
            2500,
            true,
            null,
            java.math.BigDecimal.valueOf(2.4),
            14,
            now));
    verify(jdbc)
        .update(
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
            any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcDispatchOrderAdapterBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn("1234");
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    UUID orderId = Ids.newId();
    Instant now = Instant.parse("2026-07-24T09:00:00Z");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(orderId);
              when(rs.getString("order_number")).thenReturn("MED-1");
              when(rs.getObject("pharmacy_id")).thenReturn(Ids.newId());
              when(rs.getString("business_name")).thenReturn("Apollo");
              when(rs.getString("pharmacy_fallback")).thenReturn("Apollo");
              when(rs.getObject("zone_id")).thenReturn(Ids.newId());
              when(rs.getString("zone_name")).thenReturn("HSR");
              when(rs.getInt("items_count")).thenReturn(2);
              when(rs.getLong("total_payable_paise")).thenReturn(1000L);
              when(rs.getString("payment_method")).thenReturn("UPI");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("ready_for_pickup_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("latitude")).thenReturn(12.9);
              when(rs.getObject("longitude")).thenReturn(77.6);
              when(rs.getString("status")).thenReturn("READY_FOR_PICKUP");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getString("pharmacy_name")).thenReturn("Apollo");
              when(rs.getString("pharmacy_address")).thenReturn("Addr");
              when(rs.getString("pharmacy_phone")).thenReturn("99");
              when(rs.getString("customer_name")).thenReturn("Priya");
              when(rs.getString("customer_phone")).thenReturn("98");
              when(rs.getString("delivery_address")).thenReturn("HSR");
              when(rs.getObject("delivery_lat")).thenReturn(12.9);
              when(rs.getObject("delivery_lng")).thenReturn(77.6);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("sla_deadline")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn(AssignmentOtps.hash("1234"));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(orderId);
              when(rs.getString("order_number")).thenReturn("MED-1");
              when(rs.getObject("pharmacy_id")).thenReturn(Ids.newId());
              when(rs.getString("business_name")).thenReturn("   ");
              when(rs.getString("pharmacy_fallback")).thenReturn("Fallback");
              when(rs.getObject("zone_id")).thenReturn(Ids.newId());
              when(rs.getString("zone_name")).thenReturn("HSR");
              when(rs.getInt("items_count")).thenReturn(1);
              when(rs.getLong("total_payable_paise")).thenReturn(100L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("ready_for_pickup_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("latitude")).thenReturn(null);
              when(rs.getObject("longitude")).thenReturn(null);
              when(rs.getString("delivery_otp_hash")).thenReturn("$2a$10$abcdefghijklmnopqrstuv");
              return List.of(mapper.mapRow(rs, 0));
            });

    JdbcDispatchOrderAdapter adapter = new JdbcDispatchOrderAdapter(jdbc, redis);
    assertThat(adapter.listUnassignedReady(null, 1, 20).total()).isEqualTo(1);
    assertThat(adapter.listUnassignedReady(Ids.newId(), 1, 20).total()).isEqualTo(1);
    assertThat(adapter.findOrder(orderId)).isPresent();
    adapter.assignRiderOnOrder(orderId, Ids.newId(), now);
    adapter.clearRiderOnOrder(orderId, now);
    adapter.advanceStatus(
        orderId, "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "RIDER", Ids.newId(), "n", now);
    adapter.advanceStatus(orderId, "OUT_FOR_DELIVERY", "DELIVERED", "RIDER", Ids.newId(), "n", now);
    assertThat(adapter.peekDeliveryOtp(orderId)).contains("1234");
    assertThat(adapter.verifyDeliveryOtp(orderId, "1234")).isTrue();
    assertThat(adapter.verifyDeliveryOtp(orderId, "0000")).isFalse();
    assertThat(adapter.ensureDeliveryOtp(orderId, now)).isEqualTo("1234");
    when(ops.get(anyString())).thenReturn(null);
    assertThat(adapter.ensureDeliveryOtp(orderId, now)).hasSize(4);
    assertThat(adapter.verifyDeliveryOtp(orderId, null)).isFalse();
    JdbcDispatchOrderAdapter noRedis = new JdbcDispatchOrderAdapter(jdbc);
    noRedis.peekDeliveryOtp(orderId);
    when(ops.get(anyString())).thenReturn(null);
    assertThat(noRedis.ensureDeliveryOtp(orderId, now)).hasSize(4);
    // null business_name + non-null ready_for_pickup
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(orderId);
              when(rs.getString("order_number")).thenReturn("MED-1");
              when(rs.getObject("pharmacy_id")).thenReturn(Ids.newId());
              when(rs.getString("business_name")).thenReturn(null);
              when(rs.getString("pharmacy_fallback")).thenReturn("Fallback");
              when(rs.getObject("zone_id")).thenReturn(Ids.newId());
              when(rs.getString("zone_name")).thenReturn("HSR");
              when(rs.getInt("items_count")).thenReturn(1);
              when(rs.getLong("total_payable_paise")).thenReturn(100L);
              when(rs.getString("payment_method")).thenReturn("COD");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("ready_for_pickup_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("latitude")).thenReturn(1.0);
              when(rs.getObject("longitude")).thenReturn(2.0);
              return List.of(mapper.mapRow(rs, 0));
            });
    adapter.listUnassignedReady(Ids.newId(), 1, 20);
    assertThat(new StubDistanceMatrixAdapter().distanceKm(null, null, null)).isEqualTo(5.0);
    assertThat(new StubDistanceMatrixAdapter().distanceKm(Ids.newId(), 12.9, 77.6)).isPositive();
  }

  @Test
  void redisAssignmentOtpCacheLocalFallback() {
    RedisAssignmentOtpCache cache = new RedisAssignmentOtpCache(null);
    UUID orderId = Ids.newId();
    UUID riderId = Ids.newId();
    cache.storePickupOtp(orderId, "1111");
    cache.storeDeliveryOtp(orderId, "2222");
    assertThat(cache.getPickupOtp(orderId)).contains("1111");
    assertThat(cache.getDeliveryOtp(orderId)).contains("2222");
    assertThat(cache.remainingPickupAttempts(orderId)).isEqualTo(5);
    assertThat(cache.consumePickupAttempt(orderId)).isEqualTo(4);
    cache.resetPickupAttempts(orderId);
    cache.incrConcurrent(riderId);
    cache.incrConcurrent(riderId);
    assertThat(cache.getConcurrent(riderId)).isEqualTo(2);
    cache.decrConcurrent(riderId);
    cache.setConcurrent(riderId, 0);
    cache.decrConcurrent(riderId);
    cache.evict(orderId);
    assertThat(cache.getPickupOtp(orderId)).isEmpty();
  }
}
