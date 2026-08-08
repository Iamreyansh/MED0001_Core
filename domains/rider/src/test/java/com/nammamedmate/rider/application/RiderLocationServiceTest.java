package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.nammamedmate.rider.application.port.out.GeofenceBreachStore;
import com.nammamedmate.rider.application.port.out.GeofenceBreachStore.BreachRecord;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.OrderLocationPushPort;
import com.nammamedmate.rider.application.port.out.RiderLocationCachePort.LiveLocation;
import com.nammamedmate.rider.application.port.out.RiderLocationStore;
import com.nammamedmate.rider.application.port.out.RiderLocationStore.LocationPoint;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RiderLocationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:20:30Z");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID riderId = Ids.newId();
  private final UUID customerId = Ids.newId();
  private final UUID orderId = Ids.newId();
  private final UUID zoneId = Ids.newId();
  private final ObjectMapper mapper = new ObjectMapper();

  private FakeRiders riders;
  private FakeLocations locations;
  private RedisRiderLocationCache cache;
  private FakeDeliveries deliveries;
  private FakeAssignments assignments;
  private FakeCustomerOrders customerOrders;
  private FakeGeofences geofences;
  private FakeBreaches breaches;
  private InMemoryOutboxStore outbox;
  private CountingPush push;
  private RiderLocationService service;

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    locations = new FakeLocations();
    cache = new RedisRiderLocationCache(null);
    deliveries = new FakeDeliveries();
    assignments = new FakeAssignments();
    customerOrders = new FakeCustomerOrders();
    geofences = new FakeGeofences();
    breaches = new FakeBreaches();
    outbox = new InMemoryOutboxStore();
    push = new CountingPush(new InMemoryOrderLocationPush(mapper));
    riders.insert(rider("ONLINE"));
    service =
        new RiderLocationService(
            riders,
            locations,
            cache,
            deliveries,
            assignments,
            new StubDistanceMatrixAdapter(),
            push,
            customerOrders,
            geofences,
            breaches,
            z ->
                z.equals(zoneId)
                    ? Optional.of(new ZoneLookupPort.ZoneInfo(zoneId, "Koramangala", true))
                    : Optional.empty(),
            new OutboxPublisher(outbox, mapper),
            mapper,
            clock);
  }

  @Test
  void ac001_batchIngestedToPostgresAndRedis() {
    deliveries.active.set(new ActiveOrder(orderId, "OUT_FOR_DELIVERY", "addr", 10));
    Map<String, Object> data =
        service.ingest(
            rider(),
            List.of(
                point(12.9352, 77.6245, 10.5, 22.4, 135.0, "2026-07-24T09:20:00Z"),
                point(12.9347, 77.6251, 12.1, 21.0, 138.0, "2026-07-24T09:20:10Z")));
    assertThat(data.get("points_received")).isEqualTo(2);
    assertThat(data.get("points_stored")).isEqualTo(2);
    assertThat(locations.rows).hasSize(2);
    assertThat(cache.get(riderId)).isPresent();
    assertThat(riders.lastLocationAt.get()).isEqualTo(Instant.parse("2026-07-24T09:20:10Z"));
  }

  @Test
  void ac002_lowAccuracyFlaggedAndExcludedFromEta() {
    deliveries.active.set(new ActiveOrder(orderId, "OUT_FOR_DELIVERY", "addr", 10));
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, customerId, "OUT_FOR_DELIVERY", riderId, "Ravi", 12.94, 77.63));
    Map<String, Object> data =
        service.ingest(
            rider(), List.of(point(12.9352, 77.6245, 55.0, 10.0, 90.0, "2026-07-24T09:20:20Z")));
    assertThat(data.get("points_flagged_low_accuracy")).isEqualTo(1);
    assertThat(locations.rows.get(0).lowAccuracy()).isTrue();
    assertThat(data.get("eta_minutes")).isNull();
  }

  @Test
  void ac003_customerLocationForOutForDelivery() {
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, customerId, "OUT_FOR_DELIVERY", riderId, "Ravi", 12.94, 77.63));
    cache.put(
        riderId,
        new LiveLocation(12.9347, 77.6251, 138.0, 21.0, 12.1, orderId, NOW.minusSeconds(30)),
        Duration.ofMinutes(5));
    Map<String, Object> data = service.customerRiderLocation(customer(), orderId);
    assertThat(data.get("lat")).isEqualTo(12.9347);
    assertThat(data.get("eta_minutes")).isNotNull();
    assertThat(data.get("websocket_channel").toString()).contains("rider-location/stream");
    assertThat(data.get("is_stale")).isEqualTo(false);
  }

  @Test
  void ac004_locationNotAvailableWhenNotOutForDelivery() {
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, customerId, "READY_FOR_PICKUP", riderId, "Ravi", 12.94, 77.63));
    assertThatThrownBy(() -> service.customerRiderLocation(customer(), orderId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LOCATION_NOT_AVAILABLE");
  }

  @Test
  void ac005_etaPushedViaSseOnIngest() throws Exception {
    deliveries.active.set(new ActiveOrder(orderId, "OUT_FOR_DELIVERY", "addr", 10));
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, customerId, "OUT_FOR_DELIVERY", riderId, "Ravi", 12.94, 77.63));
    SseEmitter emitter = push.subscribe(orderId);
    assertThat(emitter).isNotNull();
    service.ingest(
        rider(), List.of(point(12.9352, 77.6245, 10.0, 20.0, 100.0, "2026-07-24T09:20:25Z")));
    assertThat(push.published.get()).isEqualTo(1);
  }

  @Test
  void etaPushDeferredUntilAfterCommitWhenTxnActive() {
    deliveries.active.set(new ActiveOrder(orderId, "OUT_FOR_DELIVERY", "addr", 10));
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, customerId, "OUT_FOR_DELIVERY", riderId, "Ravi", 12.94, 77.63));
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      service.ingest(
          rider(), List.of(point(12.9352, 77.6245, 10.0, 20.0, 100.0, "2026-07-24T09:20:25Z")));
      assertThat(push.published.get()).isEqualTo(0);
      for (var sync :
          org.springframework.transaction.support.TransactionSynchronizationManager
              .getSynchronizations()) {
        sync.afterCommit();
      }
      assertThat(push.published.get()).isEqualTo(1);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .clearSynchronization();
    }
  }

  @Test
  void ac006_geofenceBreachDebounced() {
    geofences.zonesWithFence.add(zoneId);
    geofences.outside = true;
    deliveries.active.set(new ActiveOrder(orderId, "OUT_FOR_DELIVERY", "addr", 10));
    service.ingest(
        rider(), List.of(point(12.90, 77.60, 10.0, 20.0, 100.0, "2026-07-24T09:20:25Z")));
    assertThat(breaches.rows).hasSize(1);
    assertThat(outbox.all()).isNotEmpty();
    service.ingest(
        rider(), List.of(point(12.901, 77.601, 10.0, 20.0, 100.0, "2026-07-24T09:20:28Z")));
    assertThat(breaches.rows).hasSize(1);
  }

  @Test
  void ac007_historyExpiredAfterRetention() {
    Instant old = NOW.minus(Duration.ofDays(31));
    assignments.latest.put(orderId + ":" + riderId, assignment(old));
    assertThatThrownBy(() -> service.adminLocationHistory(admin(), riderId, orderId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("HISTORY_EXPIRED");
  }

  @Test
  void ac008_adminIsStaleWhenGpsOlderThanTwoMinutes() {
    cache.put(
        riderId,
        new LiveLocation(12.93, 77.62, 90.0, 15.0, 8.0, orderId, NOW.minus(Duration.ofMinutes(3))),
        Duration.ofMinutes(5));
    Map<String, Object> data = service.adminLiveLocation(admin(), riderId);
    assertThat(data.get("is_stale")).isEqualTo(true);
  }

  @Test
  void offlineRiderRejected() {
    riders.insert(rider("OFFLINE"));
    assertThatThrownBy(
            () ->
                service.ingest(
                    rider(),
                    List.of(point(12.93, 77.62, 10.0, 10.0, 10.0, "2026-07-24T09:20:00Z"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_OFFLINE");
  }

  @Test
  void emptyAndOverLimitPoints() {
    assertThatThrownBy(() -> service.ingest(rider(), List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_POINTS_ARRAY");
    List<GpsPoint> tooMany = new ArrayList<>();
    for (int i = 0; i < 61; i++) {
      tooMany.add(point(12.93, 77.62, 10.0, 10.0, 10.0, "2026-07-24T09:20:00Z"));
    }
    assertThatThrownBy(() -> service.ingest(rider(), tooMany))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("POINTS_LIMIT_EXCEEDED");
  }

  @Test
  void customerAuthAndOwnershipErrors() {
    assertThatThrownBy(() -> service.customerRiderLocation(rider(), orderId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.customerRiderLocation(customer(), Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_NOT_FOUND");
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, Ids.newId(), "OUT_FOR_DELIVERY", riderId, "Ravi", 12.94, 77.63));
    assertThatThrownBy(() -> service.customerRiderLocation(customer(), orderId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_YOUR_ORDER");
  }

  @Test
  void adminHistoryAndGeofenceHappyPaths() {
    locations.insertBatch(
        List.of(
            new LocationPoint(
                Ids.newId(),
                riderId,
                orderId,
                BigDecimal.valueOf(12.9352),
                BigDecimal.valueOf(77.6245),
                BigDecimal.TEN,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(100),
                false,
                NOW.minusSeconds(20),
                NOW)));
    Map<String, Object> hist = service.adminLocationHistory(admin(), riderId, orderId);
    assertThat(hist.get("total_points")).isEqualTo(1);

    assertThatThrownBy(() -> service.adminLocationHistory(admin(), riderId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_ID_REQUIRED");

    Map<String, Object> fence =
        service.createGeofence(
            admin(),
            zoneId,
            List.of(
                List.of(12.92, 77.61),
                List.of(12.945, 77.61),
                List.of(12.945, 77.64),
                List.of(12.92, 77.64),
                List.of(12.92, 77.61)));
    assertThat(fence.get("geofence_id")).isNotNull();
    assertThat(geofences.zonesWithFence).contains(zoneId);

    assertThatThrownBy(
            () ->
                service.createGeofence(
                    admin(),
                    zoneId,
                    List.of(
                        List.of(12.92, 77.61),
                        List.of(12.945, 77.61),
                        List.of(12.945, 77.64),
                        List.of(12.92, 77.64),
                        List.of(12.92, 77.61))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOFENCE_ALREADY_EXISTS");

    assertThatThrownBy(
            () ->
                service.createGeofence(
                    admin(), Ids.newId(), List.of(List.of(1.0, 2.0), List.of(1.0, 2.0))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ZONE_NOT_FOUND");

    assertThat(service.purgeOlderThan30Days()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void subscribeStreamAfterLocationAvailable() {
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, customerId, "OUT_FOR_DELIVERY", riderId, "Ravi", 12.94, 77.63));
    cache.put(
        riderId,
        new LiveLocation(12.93, 77.62, 1.0, 1.0, 5.0, orderId, NOW),
        Duration.ofMinutes(5));
    assertThat(service.subscribeCustomerStream(customer(), orderId)).isNotNull();
  }

  @Test
  void staleCustomerFlagAndInvalidPolygon() {
    customerOrders.byId.put(
        orderId,
        new OrderLocationContext(
            orderId, customerId, "OUT_FOR_DELIVERY", riderId, "Ravi", 12.94, 77.63));
    cache.put(
        riderId,
        new LiveLocation(12.93, 77.62, 1.0, 1.0, 5.0, orderId, NOW.minus(Duration.ofMinutes(3))),
        Duration.ofMinutes(5));
    Map<String, Object> data = service.customerRiderLocation(customer(), orderId);
    assertThat(data.get("is_stale")).isEqualTo(true);
    assertThat(data.get("stale_code")).isEqualTo("RIDER_LOCATION_STALE");

    UUID z2 = Ids.newId();
    // force zone exists via stub only for zoneId — use invalid poly on known zone after clear
    geofences.zonesWithFence.clear();
    assertThatThrownBy(
            () ->
                service.createGeofence(
                    admin(), zoneId, List.of(List.of(12.9, 77.6), List.of(12.91, 77.61))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_POLYGON");
  }

  private GpsPoint point(
      double lat, double lng, double acc, double speed, double heading, String ts) {
    return new GpsPoint(lat, lng, acc, speed, heading, Instant.parse(ts));
  }

  private MedmatePrincipal rider() {
    return new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal customer() {
    return new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal admin() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private RiderRecord rider(String status) {
    Instant t = NOW;
    return new RiderRecord(
        riderId,
        "Ravi Kumar",
        "+919876543210",
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
        BigDecimal.valueOf(4.8),
        10,
        BigDecimal.valueOf(90),
        0,
        0,
        0,
        null,
        null,
        null,
        t,
        t);
  }

  private AssignmentRecord assignment(Instant created) {
    return new AssignmentRecord(
        Ids.newId(),
        orderId,
        riderId,
        "AUTO",
        null,
        "DELIVERED",
        created,
        created,
        created,
        created,
        "hash",
        "hash",
        null,
        null,
        created,
        created);
  }

  private static final class FakeRiders implements RiderStore {
    private final ConcurrentHashMap<UUID, RiderRecord> byId = new ConcurrentHashMap<>();
    final AtomicReference<Instant> lastLocationAt = new AtomicReference<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return Optional.empty();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return false;
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(List.of(), 0);
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}

    @Override
    public void updateLastLocationAt(UUID id, Instant at) {
      lastLocationAt.set(at);
    }
  }

  private static final class FakeLocations implements RiderLocationStore {
    final List<LocationPoint> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insertBatch(List<LocationPoint> points) {
      rows.addAll(points);
    }

    @Override
    public List<LocationPoint> findByRiderAndOrder(UUID riderId, UUID orderId) {
      return rows.stream()
          .filter(p -> p.riderId().equals(riderId) && orderId.equals(p.orderId()))
          .toList();
    }

    @Override
    public Optional<Instant> findOldestRecordedAt(UUID riderId, UUID orderId) {
      return findByRiderAndOrder(riderId, orderId).stream()
          .map(LocationPoint::recordedAt)
          .min(Instant::compareTo);
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
      int before = rows.size();
      rows.removeIf(p -> p.createdAt().isBefore(cutoff));
      return before - rows.size();
    }

    @Override
    public Optional<LocationPoint> findLatestByRider(UUID riderId) {
      return rows.stream()
          .filter(p -> p.riderId().equals(riderId))
          .max((a, b) -> a.recordedAt().compareTo(b.recordedAt()));
    }
  }

  private static final class FakeDeliveries implements ActiveDeliveryPort {
    final AtomicReference<ActiveOrder> active = new AtomicReference<>();

    @Override
    public Optional<ActiveOrder> findActiveByRider(UUID riderId) {
      return Optional.ofNullable(active.get());
    }

    @Override
    public int countLiveOrdersInZone(UUID zoneId) {
      return 0;
    }

    @Override
    public void flagForMonitoring(UUID orderId, String reason) {}
  }

  private static final class FakeAssignments implements OrderAssignmentStore {
    final ConcurrentHashMap<String, AssignmentRecord> latest = new ConcurrentHashMap<>();

    @Override
    public void insert(AssignmentRecord row) {}

    @Override
    public void update(AssignmentRecord row) {}

    @Override
    public Optional<AssignmentRecord> findById(UUID id) {
      return Optional.empty();
    }

    @Override
    public Optional<AssignmentRecord> findActiveByOrder(UUID orderId) {
      return Optional.empty();
    }

    @Override
    public Optional<AssignmentRecord> findLatestByOrderAndRider(UUID orderId, UUID riderId) {
      return Optional.ofNullable(latest.get(orderId + ":" + riderId));
    }

    @Override
    public Optional<AssignmentRecord> findCurrentForRider(UUID riderId) {
      return Optional.empty();
    }

    @Override
    public int countActiveForRider(UUID riderId) {
      return 0;
    }

    @Override
    public List<AssignmentRecord> findPendingPastDeadline(Instant now, int limit) {
      return List.of();
    }

    @Override
    public boolean hasActiveForOrder(UUID orderId) {
      return false;
    }
  }

  private static final class FakeCustomerOrders implements CustomerOrderLocationPort {
    final ConcurrentHashMap<UUID, OrderLocationContext> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<OrderLocationContext> findById(UUID orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }
  }

  private static final class FakeGeofences implements DeliveryGeofenceStore {
    final CopyOnWriteArrayList<UUID> zonesWithFence = new CopyOnWriteArrayList<>();
    boolean outside;

    @Override
    public void insert(
        UUID id,
        UUID zoneId,
        String wkt,
        String coordinatesJson,
        BigDecimal areaSqKm,
        UUID createdBy,
        Instant now) {
      zonesWithFence.add(zoneId);
    }

    @Override
    public boolean existsForZone(UUID zoneId) {
      return zonesWithFence.contains(zoneId);
    }

    @Override
    public Optional<GeofenceRecord> findByZoneId(UUID zoneId) {
      return Optional.empty();
    }

    @Override
    public boolean containsPoint(UUID zoneId, double lat, double lng) {
      return !outside;
    }
  }

  private static final class FakeBreaches implements GeofenceBreachStore {
    final List<BreachRecord> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(BreachRecord row) {
      rows.add(row);
    }

    @Override
    public boolean existsSince(UUID riderId, UUID zoneId, Instant since) {
      return rows.stream()
          .anyMatch(
              b ->
                  b.riderId().equals(riderId)
                      && b.zoneId().equals(zoneId)
                      && !b.detectedAt().isBefore(since));
    }
  }

  private static final class CountingPush implements OrderLocationPushPort {
    private final OrderLocationPushPort delegate;
    final AtomicInteger published = new AtomicInteger();

    CountingPush(OrderLocationPushPort delegate) {
      this.delegate = delegate;
    }

    @Override
    public SseEmitter subscribe(UUID orderId) {
      return delegate.subscribe(orderId);
    }

    @Override
    public void publish(UUID orderId, Map<String, Object> payload) {
      published.incrementAndGet();
      delegate.publish(orderId, payload);
    }

    @Override
    public String channelUrl(UUID orderId) {
      return delegate.channelUrl(orderId);
    }
  }
}
