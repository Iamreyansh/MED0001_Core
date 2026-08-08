package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.in.web.AdminRiderLocationController;
import com.nammamedmate.rider.adapter.in.web.CustomerRiderLocationController;
import com.nammamedmate.rider.adapter.in.web.RiderLocationController;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLocationCache;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcCustomerOrderLocationAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDeliveryGeofenceStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcGeofenceBreachStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderLocationStore;
import com.nammamedmate.rider.adapter.out.sse.InMemoryOrderLocationPush;
import com.nammamedmate.rider.application.RiderLocationService.GpsPoint;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort.OrderLocationContext;
import com.nammamedmate.rider.application.port.out.DeliveryGeofenceStore;
import com.nammamedmate.rider.application.port.out.GeofenceBreachStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.RiderLocationCachePort.LiveLocation;
import com.nammamedmate.rider.application.port.out.RiderLocationStore;
import com.nammamedmate.rider.application.port.out.RiderLocationStore.LocationPoint;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RiderLocationCoverageTest {

  private final Instant now = Instant.parse("2026-07-24T10:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void controllersAndSchedulerThinWrappers() {
    RiderLocationService svc = mock(RiderLocationService.class);
    when(svc.ingest(any(), any())).thenReturn(Map.of("points_stored", 1));
    when(svc.customerRiderLocation(any(), any())).thenReturn(Map.of("lat", 1.0));
    when(svc.adminLiveLocation(any(), any())).thenReturn(Map.of("is_stale", false));
    when(svc.adminLocationHistory(any(), any(), any())).thenReturn(Map.of("total_points", 0));
    when(svc.createGeofence(any(), any(), any())).thenReturn(Map.of("geofence_id", "x"));
    when(svc.subscribeCustomerStream(any(), any()))
        .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());
    when(svc.purgeOlderThan30Days()).thenReturn(3);

    MedmatePrincipal p =
        new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j");
    RiderLocationController riderCtl = new RiderLocationController(svc);
    assertThat(
            riderCtl
                .postLocation(
                    p,
                    new RiderLocationController.LocationBatchRequest(
                        List.of(
                            new RiderLocationController.PointDto(12.9, 77.6, 10.0, 1.0, 1.0, now))))
                .data()
                .get("points_stored"))
        .isEqualTo(1);
    assertThat(riderCtl.postLocation(p, null).data().get("points_stored")).isEqualTo(1);
    assertThat(
            riderCtl
                .postLocation(p, new RiderLocationController.LocationBatchRequest(null))
                .data()
                .get("points_stored"))
        .isEqualTo(1);

    CustomerRiderLocationController cust = new CustomerRiderLocationController(svc);
    UUID oid = Ids.newId();
    assertThat(cust.riderLocation(p, oid).data()).containsEntry("lat", 1.0);
    assertThat(cust.stream(p, oid)).isNotNull();

    AdminRiderLocationController admin = new AdminRiderLocationController(svc);
    assertThat(admin.location(p, Ids.newId()).data()).containsEntry("is_stale", false);
    assertThat(admin.history(p, Ids.newId(), oid).data()).containsEntry("total_points", 0);
    assertThat(
            admin
                .createGeofence(
                    p, new AdminRiderLocationController.GeofenceRequest(Ids.newId(), List.of()))
                .data())
        .containsKey("geofence_id");
    assertThat(admin.createGeofence(p, null).data()).containsKey("geofence_id");
    assertThat(
            admin
                .createGeofence(
                    p, new AdminRiderLocationController.GeofenceRequest(Ids.newId(), null))
                .data())
        .containsKey("geofence_id");
    java.util.ArrayList<List<Double>> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    withNull.add(List.of(1.0, 2.0));
    assertThat(
            admin
                .createGeofence(
                    p, new AdminRiderLocationController.GeofenceRequest(Ids.newId(), withNull))
                .data())
        .containsKey("geofence_id");
    assertThat(
            new DeliveryGeofenceStore.GeofenceRecord(
                    Ids.newId(),
                    Ids.newId(),
                    "Z",
                    null,
                    java.math.BigDecimal.ONE,
                    Ids.newId(),
                    java.time.Instant.parse("2026-07-24T10:00:00Z"),
                    java.time.Instant.parse("2026-07-24T10:00:00Z"))
                .polygonCoordinates())
        .isEmpty();
    java.util.ArrayList<List<Double>> nestedNull = new java.util.ArrayList<>();
    nestedNull.add(null);
    assertThat(
            new DeliveryGeofenceStore.GeofenceRecord(
                    Ids.newId(),
                    Ids.newId(),
                    "Z",
                    nestedNull,
                    java.math.BigDecimal.ONE,
                    Ids.newId(),
                    java.time.Instant.parse("2026-07-24T10:00:00Z"),
                    java.time.Instant.parse("2026-07-24T10:00:00Z"))
                .polygonCoordinates()
                .get(0))
        .isEmpty();

    new RiderLocationPurgeScheduler(svc).purge();
    verify(svc).purgeOlderThan30Days();
  }

  @Test
  void redisLocationCacheAndSsePush() throws Exception {
    RedisRiderLocationCache local = new RedisRiderLocationCache(null);
    UUID id = Ids.newId();
    LiveLocation loc = new LiveLocation(12.9, 77.6, 90.0, 10.0, 5.0, null, now);
    local.put(id, loc, Duration.ofMinutes(5));
    assertThat(local.get(id)).contains(loc);
    local.evict(id);
    assertThat(local.get(id)).isEmpty();
    local.putLocalForTest(id, loc, System.currentTimeMillis() - 1);
    assertThat(local.get(id)).isEmpty();

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hash);
    org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisRiderLocationCache remote = new RedisRiderLocationCache(provider);
    remote.put(id, loc, Duration.ofMinutes(5));
    verify(hash).putAll(anyString(), any());
    Map<Object, Object> entries = new HashMap<>();
    entries.put("lat", "12.9");
    entries.put("lng", "77.6");
    entries.put("heading", "90.0");
    entries.put("speed_kmh", "10.0");
    entries.put("accuracy_m", "");
    entries.put("order_id", "");
    entries.put("updated_at", now.toString());
    when(hash.entries(anyString())).thenReturn(entries);
    assertThat(remote.get(id)).isPresent();
    when(hash.entries(anyString())).thenReturn(Map.of());
    assertThat(remote.get(id)).isEmpty();
    remote.evict(id);

    InMemoryOrderLocationPush push = new InMemoryOrderLocationPush(mapper);
    assertThat(push.channelUrl(id)).contains(id.toString());
    var emitter = push.subscribe(id);
    push.publish(id, Map.of("lat", 1));
    push.publish(Ids.newId(), Map.of("lat", 1));
    emitter.complete();
  }

  @Test
  void jdbcStoresMapRows() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID riderId = Ids.newId();
    UUID orderId = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> mapLocationRow(inv.getArgument(1), riderId, orderId));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> rm = inv.getArgument(1);
              if (sql.contains("customer_id")) {
                return mapOrderRow(rm);
              }
              if (sql.contains("polygon_coordinates") || sql.contains("zone_name")) {
                return mapGeofenceRow(rm);
              }
              return mapLocationRow(rm, riderId, orderId);
            });
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
        .thenReturn(null)
        .thenReturn(0)
        .thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any()))
        .thenReturn(null)
        .thenReturn(0)
        .thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(true);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    JdbcRiderLocationStore locs = new JdbcRiderLocationStore(jdbc);
    locs.insertBatch(
        List.of(
            new LocationPoint(
                Ids.newId(),
                riderId,
                null,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                false,
                now,
                now)));
    locs.insertBatch(List.of());
    assertThat(locs.findByRiderAndOrder(riderId, orderId)).hasSize(1);
    assertThat(locs.findOldestRecordedAt(riderId, orderId)).isPresent();
    assertThat(locs.findLatestByRider(riderId)).isPresent();
    assertThat(locs.purgeOlderThan(now)).isEqualTo(1);

    JdbcDeliveryGeofenceStore fences = new JdbcDeliveryGeofenceStore(jdbc, mapper);
    fences.insert(
        Ids.newId(),
        Ids.newId(),
        "POLYGON((0 0,1 0,1 1,0 1,0 0))",
        "[]",
        BigDecimal.ONE,
        Ids.newId(),
        now);
    assertThat(fences.existsForZone(Ids.newId())).isFalse(); // null
    assertThat(fences.existsForZone(Ids.newId())).isFalse(); // 0
    assertThat(fences.existsForZone(Ids.newId())).isTrue(); // 1
    assertThat(fences.findByZoneId(Ids.newId())).isPresent();
    assertThat(fences.containsPoint(Ids.newId(), 12.9, 77.6)).isTrue();

    JdbcGeofenceBreachStore breaches = new JdbcGeofenceBreachStore(jdbc);
    breaches.insert(
        new GeofenceBreachStore.BreachRecord(
            Ids.newId(), riderId, Ids.newId(), orderId, BigDecimal.ONE, BigDecimal.TEN, true, now));
    assertThat(breaches.existsSince(riderId, Ids.newId(), now)).isFalse();
    assertThat(breaches.existsSince(riderId, Ids.newId(), now)).isFalse();
    assertThat(breaches.existsSince(riderId, Ids.newId(), now)).isTrue();

    JdbcCustomerOrderLocationAdapter orders = new JdbcCustomerOrderLocationAdapter(jdbc);
    assertThat(orders.findById(orderId)).isPresent();

    locs.insertBatch(null);
    assertThat(
            new StubDistanceMatrixAdapter().estimateDriving(12.9, 77.6, 12.95, 77.65).distanceKm())
        .isPositive();
  }

  private List<?> mapLocationRow(RowMapper<?> rm, UUID riderId, UUID orderId) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getObject("rider_id")).thenReturn(riderId);
    when(rs.getObject("order_id")).thenReturn(orderId);
    when(rs.getBigDecimal("lat")).thenReturn(BigDecimal.ONE);
    when(rs.getBigDecimal("lng")).thenReturn(BigDecimal.TEN);
    when(rs.getBigDecimal("accuracy_m")).thenReturn(BigDecimal.ONE);
    when(rs.getBigDecimal("speed_kmh")).thenReturn(BigDecimal.ONE);
    when(rs.getBigDecimal("heading")).thenReturn(BigDecimal.ONE);
    when(rs.getBoolean("low_accuracy")).thenReturn(false);
    when(rs.getTimestamp("recorded_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("oldest")).thenReturn(Timestamp.from(now));
    return List.of(rm.mapRow(rs, 0));
  }

  private List<?> mapOrderRow(RowMapper<?> rm) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getObject("customer_id")).thenReturn(Ids.newId());
    when(rs.getObject("rider_id")).thenReturn(Ids.newId());
    when(rs.getString("status")).thenReturn("OUT_FOR_DELIVERY");
    when(rs.getString("rider_name")).thenReturn("Ravi");
    when(rs.getObject("delivery_lat")).thenReturn(12.9);
    when(rs.getObject("delivery_lng")).thenReturn(77.6);
    return List.of(rm.mapRow(rs, 0));
  }

  private List<?> mapGeofenceRow(RowMapper<?> rm) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getObject("zone_id")).thenReturn(Ids.newId());
    when(rs.getString("zone_name")).thenReturn("Z");
    when(rs.getString("coords")).thenReturn("[[12.9,77.6],[12.9,77.6]]");
    when(rs.getBigDecimal("area_sq_km")).thenReturn(BigDecimal.ONE);
    when(rs.getObject("created_by")).thenReturn(Ids.newId());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return List.of(rm.mapRow(rs, 0));
  }

  @Test
  void serviceFallbackToDbAndInsideGeofenceSkipsBreach() {
    RiderStore riders = mock(RiderStore.class);
    RiderLocationStore locations = mock(RiderLocationStore.class);
    RedisRiderLocationCache cache = new RedisRiderLocationCache(null);
    ActiveDeliveryPort deliveries = mock(ActiveDeliveryPort.class);
    OrderAssignmentStore assignments = mock(OrderAssignmentStore.class);
    CustomerOrderLocationPort customerOrders = mock(CustomerOrderLocationPort.class);
    DeliveryGeofenceStore geofences = mock(DeliveryGeofenceStore.class);
    GeofenceBreachStore breaches = mock(GeofenceBreachStore.class);
    ZoneLookupPort zones = mock(ZoneLookupPort.class);
    UUID riderId = Ids.newId();
    UUID zoneId = Ids.newId();
    UUID orderId = Ids.newId();
    when(riders.findById(riderId)).thenReturn(Optional.of(rider(riderId, zoneId, "ON_TRIP")));
    when(deliveries.findActiveByRider(riderId)).thenReturn(Optional.empty());
    when(assignments.findCurrentForRider(riderId)).thenReturn(Optional.empty());
    when(geofences.existsForZone(zoneId)).thenReturn(true);
    when(geofences.containsPoint(eq(zoneId), any(Double.class), any(Double.class)))
        .thenReturn(true);
    when(locations.findLatestByRider(riderId))
        .thenReturn(
            Optional.of(
                new LocationPoint(
                    Ids.newId(),
                    riderId,
                    orderId,
                    BigDecimal.valueOf(12.9),
                    BigDecimal.valueOf(77.6),
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    false,
                    now.minusSeconds(30),
                    now)));

    RiderLocationService service =
        new RiderLocationService(
            riders,
            locations,
            cache,
            deliveries,
            assignments,
            new StubDistanceMatrixAdapter(),
            new InMemoryOrderLocationPush(mapper),
            customerOrders,
            geofences,
            breaches,
            zones,
            new OutboxPublisher(new InMemoryOutboxStore(), mapper),
            mapper,
            clock);

    MedmatePrincipal rider =
        new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
    service.ingest(rider, List.of(new GpsPoint(12.9, 77.6, 10.0, 10.0, 10.0, now.minusSeconds(5))));

    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Map<String, Object> live = service.adminLiveLocation(admin, riderId);
    assertThat(live.get("is_in_zone")).isEqualTo(true);

    when(customerOrders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderLocationContext(
                    orderId, Ids.newId(), "OUT_FOR_DELIVERY", riderId, "R", null, null)));
    assertThatThrownBy(
            () ->
                service.customerRiderLocation(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    orderId))
        .isInstanceOf(AppException.class);
  }

  private static RiderRecord rider(UUID id, UUID zoneId, String status) {
    Instant t = Instant.parse("2026-07-24T10:00:00Z");
    return new RiderRecord(
        id,
        "R",
        "+919999999999",
        null,
        "BIKE",
        "KA01AB1234",
        zoneId,
        status,
        "APPROVED",
        t,
        t,
        null,
        null,
        null,
        false,
        null,
        0,
        null,
        0,
        0,
        0,
        null,
        null,
        null,
        t,
        t);
  }
}
