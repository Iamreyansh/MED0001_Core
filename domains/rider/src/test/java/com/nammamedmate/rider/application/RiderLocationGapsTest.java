package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLocationCache;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.adapter.out.sse.InMemoryOrderLocationPush;
import com.nammamedmate.rider.application.RiderLocationService.GpsPoint;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort.ActiveOrder;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort.OrderLocationContext;
import com.nammamedmate.rider.application.port.out.DeliveryGeofenceStore;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.GeofenceBreachStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.RiderLocationCachePort.LiveLocation;
import com.nammamedmate.rider.application.port.out.RiderLocationStore;
import com.nammamedmate.rider.application.port.out.RiderLocationStore.LocationPoint;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.rider.domain.GeofencePolygons;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RiderLocationGapsTest {

  private final Instant now = Instant.parse("2026-07-24T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void ingestEdgeBranchesAndFallbacks() {
    UUID riderId = Ids.newId();
    UUID zoneId = Ids.newId();
    UUID orderId = Ids.newId();
    RiderStore riders = mock(RiderStore.class);
    RiderLocationStore locations = mock(RiderLocationStore.class);
    RedisRiderLocationCache cache = new RedisRiderLocationCache(null);
    ActiveDeliveryPort deliveries = mock(ActiveDeliveryPort.class);
    OrderAssignmentStore assignments = mock(OrderAssignmentStore.class);
    CustomerOrderLocationPort customerOrders = mock(CustomerOrderLocationPort.class);
    DeliveryGeofenceStore geofences = mock(DeliveryGeofenceStore.class);
    GeofenceBreachStore breaches = mock(GeofenceBreachStore.class);
    ZoneLookupPort zones = mock(ZoneLookupPort.class);

    when(riders.findById(riderId)).thenReturn(Optional.empty());
    RiderLocationService svc =
        service(
            riders,
            locations,
            cache,
            deliveries,
            assignments,
            customerOrders,
            geofences,
            breaches,
            zones);
    MedmatePrincipal rider = p(riderId, AuthRole.RIDER);
    assertThatThrownBy(() -> svc.ingest(rider, List.of(pt(12.9, 77.6, 10.0, now))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    when(riders.findById(riderId)).thenReturn(Optional.of(rec(riderId, zoneId, "ONLINE")));
    assertThatThrownBy(() -> svc.ingest(rider, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EMPTY_POINTS_ARRAY");
    java.util.ArrayList<GpsPoint> invalid = new java.util.ArrayList<>();
    invalid.add(null);
    invalid.add(new GpsPoint(null, 1.0, 1.0, 1.0, 1.0, now));
    invalid.add(new GpsPoint(1.0, null, 1.0, 1.0, 1.0, now));
    invalid.add(new GpsPoint(1.0, 1.0, 1.0, 1.0, 1.0, null));
    assertThatThrownBy(() -> svc.ingest(rider, invalid))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EMPTY_POINTS_ARRAY");

    when(deliveries.findActiveByRider(riderId)).thenReturn(Optional.empty());
    when(assignments.findCurrentForRider(riderId))
        .thenReturn(
            Optional.of(
                new AssignmentRecord(
                    Ids.newId(),
                    orderId,
                    riderId,
                    "AUTO",
                    null,
                    "PICKED_UP",
                    now,
                    now,
                    now,
                    null,
                    "h",
                    "h",
                    null,
                    null,
                    now,
                    now)));
    when(customerOrders.findById(orderId)).thenReturn(Optional.empty());
    when(geofences.existsForZone(zoneId)).thenReturn(false);

    // device-stale only → redis uses latest, no ETA push
    Map<String, Object> data =
        svc.ingest(
            rider,
            List.of(
                pt(12.9, 77.6, null, now.minusSeconds(120)),
                pt(12.91, 77.61, 10.0, now.minusSeconds(5)),
                new GpsPoint(12.92, 77.62, 10.0, null, null, now.minusSeconds(4))));
    assertThat(data.get("points_stored")).isEqualTo(3);
    assertThat(cache.get(riderId)).isPresent();

    // null zone skips breach; no fence
    when(riders.findById(riderId)).thenReturn(Optional.of(rec(riderId, null, "ON_TRIP")));
    svc.ingest(rider, List.of(pt(12.9, 77.6, 10.0, now.minusSeconds(1))));

    // same timestamp twice + order not OFD → no ETA push
    when(riders.findById(riderId)).thenReturn(Optional.of(rec(riderId, zoneId, "ONLINE")));
    when(deliveries.findActiveByRider(riderId))
        .thenReturn(Optional.of(new ActiveOrder(orderId, "READY_FOR_PICKUP", "a", 5)));
    when(customerOrders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderLocationContext(
                    orderId, Ids.newId(), "READY_FOR_PICKUP", riderId, "R", 12.9, 77.6)));
    Instant same = now.minusSeconds(2);
    svc.ingest(rider, List.of(pt(12.9, 77.6, 10.0, same), pt(12.9, 77.6, 10.0, same)));
  }

  @Test
  void customerAndAdminRemainingBranches() {
    UUID riderId = Ids.newId();
    UUID customerId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID zoneId = Ids.newId();
    RiderStore riders = mock(RiderStore.class);
    RiderLocationStore locations = mock(RiderLocationStore.class);
    RedisRiderLocationCache cache = new RedisRiderLocationCache(null);
    ActiveDeliveryPort deliveries = mock(ActiveDeliveryPort.class);
    OrderAssignmentStore assignments = mock(OrderAssignmentStore.class);
    CustomerOrderLocationPort customerOrders = mock(CustomerOrderLocationPort.class);
    DeliveryGeofenceStore geofences = mock(DeliveryGeofenceStore.class);
    GeofenceBreachStore breaches = mock(GeofenceBreachStore.class);
    ZoneLookupPort zones = mock(ZoneLookupPort.class);
    RiderLocationService svc =
        service(
            riders,
            locations,
            cache,
            deliveries,
            assignments,
            customerOrders,
            geofences,
            breaches,
            zones);

    when(customerOrders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderLocationContext(
                    orderId, customerId, "OUT_FOR_DELIVERY", null, "R", 12.9, 77.6)));
    assertThatThrownBy(() -> svc.customerRiderLocation(p(customerId, AuthRole.CUSTOMER), orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("LOCATION_NOT_AVAILABLE");

    when(customerOrders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderLocationContext(
                    orderId, customerId, "OUT_FOR_DELIVERY", riderId, "R", 12.9, 77.6)));
    when(locations.findLatestByRider(riderId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> svc.customerRiderLocation(p(customerId, AuthRole.CUSTOMER), orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("LOCATION_NOT_AVAILABLE");

    when(locations.findLatestByRider(riderId))
        .thenReturn(
            Optional.of(
                new LocationPoint(
                    Ids.newId(),
                    riderId,
                    orderId,
                    BigDecimal.valueOf(12.9),
                    BigDecimal.valueOf(77.6),
                    null,
                    null,
                    null,
                    false,
                    now,
                    now)));
    Map<String, Object> fromDb =
        svc.customerRiderLocation(p(customerId, AuthRole.CUSTOMER), orderId);
    assertThat(fromDb.get("lat")).isEqualTo(12.9);
    assertThat(fromDb.get("heading")).isNull();
    cache.evict(riderId);
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
                    BigDecimal.valueOf(90),
                    false,
                    now,
                    now)));
    assertThat(svc.customerRiderLocation(p(customerId, AuthRole.CUSTOMER), orderId).get("heading"))
        .isEqualTo(90.0);

    cache.put(
        riderId, new LiveLocation(12.9, 77.6, 1.0, 1.0, 60.0, orderId, now), Duration.ofMinutes(5));
    when(customerOrders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderLocationContext(
                    orderId, customerId, "OUT_FOR_DELIVERY", riderId, "R", 12.9, 77.6)));
    assertThat(
            svc.customerRiderLocation(p(customerId, AuthRole.CUSTOMER), orderId).get("eta_minutes"))
        .isNull(); // low accuracy skips ETA
    cache.put(
        riderId, new LiveLocation(12.9, 77.6, 1.0, 1.0, 5.0, orderId, now), Duration.ofMinutes(5));
    when(customerOrders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderLocationContext(
                    orderId, customerId, "OUT_FOR_DELIVERY", riderId, "R", null, 77.6)));
    assertThat(
            svc.customerRiderLocation(p(customerId, AuthRole.CUSTOMER), orderId).get("eta_minutes"))
        .isNull(); // lat null
    when(customerOrders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderLocationContext(
                    orderId, customerId, "OUT_FOR_DELIVERY", riderId, "R", 12.9, null)));
    assertThat(
            svc.customerRiderLocation(p(customerId, AuthRole.CUSTOMER), orderId).get("eta_minutes"))
        .isNull(); // lng null

    when(riders.findById(riderId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> svc.adminLiveLocation(p(Ids.newId(), AuthRole.ADMIN_SUPER), riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    when(riders.findById(riderId)).thenReturn(Optional.of(rec(riderId, null, "ONLINE")));
    when(deliveries.findActiveByRider(riderId)).thenReturn(Optional.empty());
    cache.evict(riderId);
    when(locations.findLatestByRider(riderId)).thenReturn(Optional.empty());
    Map<String, Object> staleNull =
        svc.adminLiveLocation(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId);
    assertThat(staleNull.get("is_stale")).isEqualTo(true);
    assertThat(staleNull.get("lat")).isNull();

    cache.put(
        riderId, new LiveLocation(12.9, 77.6, 1.0, 1.0, 5.0, orderId, now), Duration.ofMinutes(5));
    assertThat(
            svc.adminLiveLocation(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId)
                .get("zone_id"))
        .isNull();
    assertThat(
            svc.adminLiveLocation(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId)
                .get("is_in_zone"))
        .isEqualTo(true);

    when(locations.findLatestByRider(riderId))
        .thenReturn(
            Optional.of(
                new LocationPoint(
                    Ids.newId(),
                    riderId,
                    null,
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(45),
                    true,
                    now.minusSeconds(10),
                    now)));
    when(riders.findById(riderId)).thenReturn(Optional.of(rec(riderId, zoneId, "ONLINE")));
    when(geofences.existsForZone(zoneId)).thenReturn(false);
    cache.evict(riderId);
    Map<String, Object> adminFromDb =
        svc.adminLiveLocation(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId);
    assertThat(adminFromDb.get("is_in_zone")).isEqualTo(true);
    assertThat(adminFromDb.get("heading")).isEqualTo(45.0);

    when(assignments.findLatestByOrderAndRider(orderId, riderId))
        .thenReturn(
            Optional.of(
                new AssignmentRecord(
                    Ids.newId(),
                    orderId,
                    riderId,
                    "AUTO",
                    null,
                    "DELIVERED",
                    now.minus(Duration.ofDays(1)),
                    now,
                    now,
                    now,
                    "h",
                    "h",
                    null,
                    null,
                    now.minus(Duration.ofDays(1)),
                    now)));
    when(locations.findByRiderAndOrder(riderId, orderId)).thenReturn(List.of());
    assertThat(
            svc.adminLocationHistory(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId, orderId)
                .get("total_points"))
        .isEqualTo(0);

    when(deliveries.findActiveByRider(riderId))
        .thenReturn(Optional.of(new ActiveOrder(orderId, "OUT_FOR_DELIVERY", "a", 5)));
    when(geofences.existsForZone(zoneId)).thenReturn(true);
    when(geofences.containsPoint(any(), any(Double.class), any(Double.class))).thenReturn(false);
    assertThat(
            svc.adminLiveLocation(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId)
                .get("is_in_zone"))
        .isEqualTo(false);

    assertThatThrownBy(
            () ->
                svc.adminLocationHistory(
                    p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), Ids.newId(), orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    when(riders.findById(riderId)).thenReturn(Optional.of(rec(riderId, zoneId, "ONLINE")));
    when(locations.findByRiderAndOrder(riderId, orderId)).thenReturn(List.of());
    when(assignments.findLatestByOrderAndRider(orderId, riderId)).thenReturn(Optional.empty());
    assertThat(
            svc.adminLocationHistory(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId, orderId)
                .get("total_points"))
        .isEqualTo(0);

    when(locations.findByRiderAndOrder(riderId, orderId))
        .thenReturn(
            List.of(
                ptRow(riderId, orderId, 12.9, 77.6, now.minus(Duration.ofDays(31))),
                ptRow(
                    riderId,
                    orderId,
                    12.91,
                    77.61,
                    now.minus(Duration.ofDays(31)).plusSeconds(10))));
    assertThatThrownBy(
            () ->
                svc.adminLocationHistory(
                    p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId, orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HISTORY_EXPIRED");

    when(locations.findByRiderAndOrder(riderId, orderId))
        .thenReturn(
            List.of(
                ptRow(riderId, orderId, 12.9, 77.6, now.minusSeconds(30)),
                ptRow(riderId, orderId, 12.91, 77.61, now.minusSeconds(20))));
    assertThat(
            ((Number)
                    svc.adminLocationHistory(
                            p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), riderId, orderId)
                        .get("total_points"))
                .intValue())
        .isEqualTo(2);

    assertThatThrownBy(
            () -> svc.createGeofence(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), null, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NOT_FOUND");
    assertThatThrownBy(
            () ->
                svc.createGeofence(
                    p(Ids.newId(), AuthRole.CUSTOMER), zoneId, List.of(List.of(1.0, 2.0))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> svc.ingest(null, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () -> svc.ingest(p(customerId, AuthRole.CUSTOMER), List.of(pt(1, 1, 1.0, now))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> svc.customerRiderLocation(null, orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> svc.customerRiderLocation(p(riderId, AuthRole.RIDER), orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> svc.adminLiveLocation(null, riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> svc.adminLiveLocation(p(Ids.newId(), AuthRole.ADMIN_FINANCE), riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(zones.findById(zoneId))
        .thenReturn(Optional.of(new ZoneLookupPort.ZoneInfo(zoneId, "Z", true)));
    when(geofences.existsForZone(zoneId)).thenReturn(false);
    assertThatThrownBy(
            () -> svc.createGeofence(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), zoneId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () ->
                svc.createGeofence(
                    p(Ids.newId(), AuthRole.ADMIN_OPERATIONS),
                    zoneId,
                    List.of(List.of(1.0), List.of(2.0, 3.0), List.of(3.0, 4.0), List.of(1.0))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    java.util.ArrayList<List<Double>> bad = new java.util.ArrayList<>();
    bad.add(null);
    bad.add(List.of(1.0, 2.0));
    bad.add(List.of(2.0, 3.0));
    bad.add(List.of(1.0, 2.0));
    assertThatThrownBy(
            () -> svc.createGeofence(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), zoneId, bad))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    java.util.ArrayList<Double> nullCoord = new java.util.ArrayList<>();
    nullCoord.add(null);
    nullCoord.add(1.0);
    assertThatThrownBy(
            () ->
                svc.createGeofence(
                    p(Ids.newId(), AuthRole.ADMIN_OPERATIONS),
                    zoneId,
                    List.of(nullCoord, List.of(2.0, 3.0), List.of(3.0, 4.0), nullCoord)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    java.util.ArrayList<Double> nullLng = new java.util.ArrayList<>();
    nullLng.add(1.0);
    nullLng.add(null);
    assertThatThrownBy(
            () ->
                svc.createGeofence(
                    p(Ids.newId(), AuthRole.ADMIN_OPERATIONS),
                    zoneId,
                    List.of(nullLng, List.of(2.0, 3.0), List.of(3.0, 4.0), nullLng)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
    assertThatThrownBy(
            () -> svc.createGeofence(p(Ids.newId(), AuthRole.ADMIN_OPERATIONS), zoneId, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");
  }

  @Test
  void geofenceJsonFailAndBreachWithoutOrder() throws Exception {
    UUID riderId = Ids.newId();
    UUID zoneId = Ids.newId();
    ObjectMapper bad = mock(ObjectMapper.class);
    when(bad.writeValueAsString(any())).thenThrow(new RuntimeException("x"));
    RiderStore riders = mock(RiderStore.class);
    when(riders.findById(riderId)).thenReturn(Optional.of(rec(riderId, zoneId, "ONLINE")));
    DeliveryGeofenceStore geofences = mock(DeliveryGeofenceStore.class);
    when(geofences.existsForZone(zoneId)).thenReturn(false);
    ZoneLookupPort zones = mock(ZoneLookupPort.class);
    when(zones.findById(zoneId))
        .thenReturn(Optional.of(new ZoneLookupPort.ZoneInfo(zoneId, "Z", true)));
    RiderLocationService svc =
        new RiderLocationService(
            riders,
            mock(RiderLocationStore.class),
            new RedisRiderLocationCache(null),
            mock(ActiveDeliveryPort.class),
            mock(OrderAssignmentStore.class),
            new StubDistanceMatrixAdapter(),
            new InMemoryOrderLocationPush(mapper),
            mock(CustomerOrderLocationPort.class),
            geofences,
            mock(GeofenceBreachStore.class),
            zones,
            new OutboxPublisher(new InMemoryOutboxStore(), mapper),
            bad,
            clock);
    assertThatThrownBy(
            () ->
                svc.createGeofence(
                    p(Ids.newId(), AuthRole.ADMIN_OPERATIONS),
                    zoneId,
                    List.of(
                        List.of(12.92, 77.61),
                        List.of(12.945, 77.61),
                        List.of(12.945, 77.64),
                        List.of(12.92, 77.64),
                        List.of(12.92, 77.61))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_POLYGON");

    // breach with null order_id in payload
    when(geofences.existsForZone(zoneId)).thenReturn(true);
    when(geofences.containsPoint(any(), any(Double.class), any(Double.class))).thenReturn(false);
    GeofenceBreachStore breaches = mock(GeofenceBreachStore.class);
    when(breaches.existsSince(any(), any(), any())).thenReturn(false);
    ActiveDeliveryPort deliveries = mock(ActiveDeliveryPort.class);
    when(deliveries.findActiveByRider(riderId)).thenReturn(Optional.empty());
    OrderAssignmentStore assignments = mock(OrderAssignmentStore.class);
    when(assignments.findCurrentForRider(riderId)).thenReturn(Optional.empty());
    RiderLocationService svc2 =
        new RiderLocationService(
            riders,
            mock(RiderLocationStore.class),
            new RedisRiderLocationCache(null),
            deliveries,
            assignments,
            new StubDistanceMatrixAdapter(),
            new InMemoryOrderLocationPush(mapper),
            mock(CustomerOrderLocationPort.class),
            geofences,
            breaches,
            zones,
            new OutboxPublisher(new InMemoryOutboxStore(), mapper),
            mapper,
            clock);
    svc2.ingest(p(riderId, AuthRole.RIDER), List.of(pt(12.9, 77.6, 10.0, now.minusSeconds(1))));
  }

  @Test
  void redisSseDomainAndDefaultPorts() throws Exception {
    assertThat(
            GeofencePolygons.isValidClosed(
                List.of(new double[] {1}, new double[] {1}, new double[] {1}, new double[] {1})))
        .isFalse();
    assertThat(
            GeofencePolygons.isValidClosed(
                List.of(
                    new double[] {1, 2},
                    new double[] {2, 3},
                    new double[] {3, 4},
                    new double[] {9, 9})))
        .isFalse();
    java.util.ArrayList<double[]> nullRing = new java.util.ArrayList<>();
    nullRing.add(null);
    nullRing.add(null);
    nullRing.add(null);
    nullRing.add(null);
    assertThat(GeofencePolygons.isValidClosed(nullRing)).isFalse();
    assertThat(GeofencePolygons.approxAreaSqKm(List.of(new double[] {1, 1}))).isZero();

    DistanceMatrixPort def = (riderId, lat, lng) -> 1.0;
    assertThat(def.estimateDriving(1, 2, 3, 4).durationMinutes()).isEqualTo(5);
    new RiderStore() {
      public void insert(RiderRecord r) {}

      public Optional<RiderRecord> findById(UUID id) {
        return Optional.empty();
      }

      public Optional<RiderRecord> findByPhone(String phone) {
        return Optional.empty();
      }

      public boolean existsByPhone(String phone) {
        return false;
      }

      public void update(RiderRecord rider) {}

      public PageResult list(ListFilter filter) {
        return new PageResult(List.of(), 0);
      }

      public void updateAvailability(
          UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

      public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}
    }.updateLastLocationAt(Ids.newId(), now);
    assertThat(
            new OrderAssignmentStore() {
              public void insert(AssignmentRecord row) {}

              public void update(AssignmentRecord row) {}

              public Optional<AssignmentRecord> findById(UUID id) {
                return Optional.empty();
              }

              public Optional<AssignmentRecord> findActiveByOrder(UUID orderId) {
                return Optional.empty();
              }

              public Optional<AssignmentRecord> findCurrentForRider(UUID riderId) {
                return Optional.empty();
              }

              public int countActiveForRider(UUID riderId) {
                return 0;
              }

              public List<AssignmentRecord> findPendingPastDeadline(Instant now, int limit) {
                return List.of();
              }

              public boolean hasActiveForOrder(UUID orderId) {
                return false;
              }
            }.findLatestByOrderAndRider(Ids.newId(), Ids.newId()))
        .isEmpty();

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hash);
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisRiderLocationCache remote = new RedisRiderLocationCache(provider);
    UUID id = Ids.newId();
    UUID oid = Ids.newId();
    remote.put(id, new LiveLocation(1, 2, null, null, null, oid, now), Duration.ofMinutes(1));
    Map<Object, Object> entries = new HashMap<>();
    entries.put("lat", "1");
    entries.put("lng", "2");
    entries.put("heading", "");
    entries.put("speed_kmh", null);
    entries.put("accuracy_m", "3.5");
    entries.put("order_id", oid.toString());
    entries.put("updated_at", now.toString());
    when(hash.entries(anyString())).thenReturn(entries);
    assertThat(remote.get(id)).isPresent();
    assertThat(remote.get(id).orElseThrow().orderId()).isEqualTo(oid);

    ObjectMapper badMapper = mock(ObjectMapper.class);
    when(badMapper.writeValueAsString(any())).thenThrow(new RuntimeException("nope"));
    InMemoryOrderLocationPush push = new InMemoryOrderLocationPush(badMapper);
    SseEmitter e = push.subscribe(id);
    push.publish(id, Map.of("x", 1));
    // IOException on send path: use real mapper then complete emitter mid-flight
    InMemoryOrderLocationPush push2 = new InMemoryOrderLocationPush(mapper);
    SseEmitter e2 = push2.subscribe(id);
    SseEmitter e3 = push2.subscribe(id);
    push2.removeEmitter(id, e3);
    push2.removeEmitter(Ids.newId(), e3);
    push2.publish(Ids.newId(), Map.of("z", 1)); // null list
    push2.removeEmitter(id, e3);
    push2.removeEmitter(id, e2); // empty list remains keyed
    push2.publish(id, Map.of("empty", 1));
    SseEmitter e4 = push2.subscribe(id);
    e4.complete();
    SseEmitter e5 = push2.subscribe(id);
    e5.completeWithError(new RuntimeException("boom"));
    SseEmitter e6 = push2.subscribe(id);
    // force timeout callback path used by SSE lifecycle
    e6.onTimeout(() -> push2.removeEmitter(id, e6));
    e6.complete();
    push2.publish(id, Map.of("y", 2)); // send throws → catch remove

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    new com.nammamedmate.rider.adapter.out.persistence.JdbcRiderStore(jdbc)
        .updateLastLocationAt(id, now);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("order_id")).thenReturn(oid);
              when(rs.getObject("rider_id")).thenReturn(id);
              when(rs.getString("assignment_type")).thenReturn("AUTO");
              when(rs.getObject("assigned_by")).thenReturn(null);
              when(rs.getString("status")).thenReturn("DELIVERED");
              when(rs.getTimestamp("accept_deadline")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("accepted_at")).thenReturn(null);
              when(rs.getTimestamp("pickup_confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("delivered_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("pickup_otp_hash")).thenReturn("h");
              when(rs.getString("delivery_otp_hash")).thenReturn("h");
              when(rs.getString("reassign_reason")).thenReturn(null);
              when(rs.getBigDecimal("composite_score")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(rm.mapRow(rs, 0));
            });
    assertThat(
            new com.nammamedmate.rider.adapter.out.persistence.JdbcOrderAssignmentStore(jdbc)
                .findLatestByOrderAndRider(oid, id))
        .isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("zone_id")).thenReturn(Ids.newId());
              when(rs.getString("zone_name")).thenReturn("Z");
              when(rs.getString("coords")).thenReturn("not-json");
              when(rs.getBigDecimal("area_sq_km")).thenReturn(BigDecimal.ONE);
              when(rs.getObject("created_by")).thenReturn(Ids.newId());
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              return List.of(rm.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any()))
        .thenReturn(0);
    var fenceStore =
        new com.nammamedmate.rider.adapter.out.persistence.JdbcDeliveryGeofenceStore(jdbc, mapper);
    assertThat(fenceStore.existsForZone(Ids.newId())).isFalse();
    assertThat(fenceStore.findByZoneId(Ids.newId())).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("rider_id")).thenReturn(id);
              when(rs.getObject("order_id")).thenReturn(null);
              when(rs.getBigDecimal("lat")).thenReturn(BigDecimal.ONE);
              when(rs.getBigDecimal("lng")).thenReturn(BigDecimal.TEN);
              when(rs.getBigDecimal("accuracy_m")).thenReturn(null);
              when(rs.getBigDecimal("speed_kmh")).thenReturn(null);
              when(rs.getBigDecimal("heading")).thenReturn(null);
              when(rs.getBoolean("low_accuracy")).thenReturn(false);
              when(rs.getTimestamp("recorded_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("oldest")).thenReturn(null);
              return List.of(rm.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("oldest")).thenReturn(null);
              java.util.ArrayList<Object> out = new java.util.ArrayList<>();
              out.add(rm.mapRow(rs, 0));
              return out;
            });
    var locStore = new com.nammamedmate.rider.adapter.out.persistence.JdbcRiderLocationStore(jdbc);
    assertThat(locStore.findLatestByRider(id)).isPresent();
    assertThat(locStore.findOldestRecordedAt(id, oid)).isEmpty();
  }

  private RiderLocationService service(
      RiderStore riders,
      RiderLocationStore locations,
      RedisRiderLocationCache cache,
      ActiveDeliveryPort deliveries,
      OrderAssignmentStore assignments,
      CustomerOrderLocationPort customerOrders,
      DeliveryGeofenceStore geofences,
      GeofenceBreachStore breaches,
      ZoneLookupPort zones) {
    return new RiderLocationService(
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
  }

  private static MedmatePrincipal p(UUID id, AuthRole role) {
    return new MedmatePrincipal(id, role, null, TokenScope.FULL, "j");
  }

  private static GpsPoint pt(double lat, double lng, Double acc, Instant ts) {
    return new GpsPoint(lat, lng, acc, 10.0, 90.0, ts);
  }

  private static LocationPoint ptRow(
      UUID riderId, UUID orderId, double lat, double lng, Instant ts) {
    return new LocationPoint(
        Ids.newId(),
        riderId,
        orderId,
        BigDecimal.valueOf(lat),
        BigDecimal.valueOf(lng),
        BigDecimal.TEN,
        BigDecimal.ONE,
        BigDecimal.ONE,
        false,
        ts,
        ts);
  }

  private static RiderRecord rec(UUID id, UUID zoneId, String status) {
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
        nowFixed(),
        nowFixed(),
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
        nowFixed(),
        nowFixed());
  }

  private static Instant nowFixed() {
    return Instant.parse("2026-07-24T12:00:00Z");
  }
}
