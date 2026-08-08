package com.nammamedmate.rider.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort.OrderLocationContext;
import com.nammamedmate.rider.application.port.out.DeliveryGeofenceStore;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort.RouteEstimate;
import com.nammamedmate.rider.application.port.out.GeofenceBreachStore;
import com.nammamedmate.rider.application.port.out.GeofenceBreachStore.BreachRecord;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderLocationPushPort;
import com.nammamedmate.rider.application.port.out.RiderLocationCachePort;
import com.nammamedmate.rider.application.port.out.RiderLocationCachePort.LiveLocation;
import com.nammamedmate.rider.application.port.out.RiderLocationStore;
import com.nammamedmate.rider.application.port.out.RiderLocationStore.LocationPoint;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.rider.domain.GeofencePolygons;
import com.nammamedmate.rider.domain.RiderAvailability;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RiderLocationService {

  private static final Set<String> ACCEPT_STATUSES = Set.of("ONLINE", "ON_TRIP");
  private static final Duration REDIS_TTL = Duration.ofMinutes(5);
  private static final Duration BREACH_DEBOUNCE = Duration.ofMinutes(5);
  private static final Duration DEVICE_STALE = Duration.ofSeconds(60);
  private static final double ACCURACY_LIMIT_M = 50.0;
  private static final int MAX_POINTS = 60;
  private static final Duration HISTORY_RETENTION = Duration.ofDays(30);
  private static final Set<AuthRole> OPS = Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);

  private final RiderStore riders;
  private final RiderLocationStore locations;
  private final RiderLocationCachePort locationCache;
  private final ActiveDeliveryPort deliveries;
  private final OrderAssignmentStore assignments;
  private final DistanceMatrixPort distance;
  private final OrderLocationPushPort push;
  private final CustomerOrderLocationPort customerOrders;
  private final DeliveryGeofenceStore geofences;
  private final GeofenceBreachStore breaches;
  private final ZoneLookupPort zones;
  private final OutboxPublisher outbox;
  private final ObjectMapper mapper;
  private final Clock clock;

  public RiderLocationService(
      RiderStore riders,
      RiderLocationStore locations,
      RiderLocationCachePort locationCache,
      ActiveDeliveryPort deliveries,
      OrderAssignmentStore assignments,
      DistanceMatrixPort distance,
      OrderLocationPushPort push,
      CustomerOrderLocationPort customerOrders,
      DeliveryGeofenceStore geofences,
      GeofenceBreachStore breaches,
      ZoneLookupPort zones,
      OutboxPublisher outbox,
      ObjectMapper mapper,
      Clock clock) {
    this.riders = riders;
    this.locations = locations;
    this.locationCache = locationCache;
    this.deliveries = deliveries;
    this.assignments = assignments;
    this.distance = distance;
    this.push = push;
    this.customerOrders = customerOrders;
    this.geofences = geofences;
    this.breaches = breaches;
    this.zones = zones;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> ingest(MedmatePrincipal principal, List<GpsPoint> points) {
    requireRider(principal);
    UUID riderId = principal.subject();
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));

    if (!ACCEPT_STATUSES.contains(rider.status())) {
      throw new AppException(
          "RIDER_OFFLINE", "Rider status is not ONLINE or ON_TRIP; update discarded", 422);
    }
    if (points == null || points.isEmpty()) {
      throw new AppException("EMPTY_POINTS_ARRAY", "points array is empty", 422);
    }
    if (points.size() > MAX_POINTS) {
      throw new AppException("POINTS_LIMIT_EXCEEDED", "Batch contains more than 60 points", 422);
    }

    Instant now = clock.instant();
    UUID activeOrderId = deliveries.findActiveByRider(riderId).map(a -> a.orderId()).orElse(null);
    if (activeOrderId == null) {
      activeOrderId = assignments.findCurrentForRider(riderId).map(a -> a.orderId()).orElse(null);
    }

    List<LocationPoint> rows = new ArrayList<>(points.size());
    int lowAccuracyCount = 0;
    GpsPoint latest = null;
    GpsPoint etaSource = null;

    for (GpsPoint p : points) {
      if (p != null && p.lat() != null && p.lng() != null && p.timestamp() != null) {
        boolean low = p.accuracy() != null && p.accuracy() > ACCURACY_LIMIT_M;
        if (low) {
          lowAccuracyCount++;
        }
        Instant recorded = p.timestamp();
        rows.add(
            new LocationPoint(
                Ids.newId(),
                riderId,
                activeOrderId,
                bd(p.lat(), 7),
                bd(p.lng(), 7),
                p.accuracy() == null ? null : bd(p.accuracy(), 2),
                p.speed() == null ? null : bd(p.speed(), 2),
                p.heading() == null ? null : bd(p.heading(), 2),
                low,
                recorded,
                now));
        if (latest == null || recorded.isAfter(latest.timestamp())) {
          latest = p;
        }
        boolean deviceStale = Duration.between(recorded, now).compareTo(DEVICE_STALE) > 0;
        if (!low && !deviceStale) {
          if (etaSource == null || recorded.isAfter(etaSource.timestamp())) {
            etaSource = p;
          }
        }
      }
    }

    if (rows.isEmpty()) {
      throw new AppException("EMPTY_POINTS_ARRAY", "points array is empty", 422);
    }
    GpsPoint latestPoint = latest;

    locations.insertBatch(rows);
    riders.updateLastLocationAt(riderId, latestPoint.timestamp());

    GpsPoint redisPoint = etaSource != null ? etaSource : latestPoint;
    locationCache.put(
        riderId,
        new LiveLocation(
            redisPoint.lat(),
            redisPoint.lng(),
            redisPoint.heading(),
            redisPoint.speed(),
            redisPoint.accuracy(),
            activeOrderId,
            redisPoint.timestamp()),
        REDIS_TTL);

    Integer etaMinutes = null;
    Double distanceKm = null;
    if (etaSource != null && activeOrderId != null) {
      Optional<OrderLocationContext> order = customerOrders.findById(activeOrderId);
      if (order.isPresent() && canComputeEta(order.get())) {
        RouteEstimate est =
            distance.estimateDriving(
                etaSource.lat(),
                etaSource.lng(),
                order.get().deliveryLat(),
                order.get().deliveryLng());
        etaMinutes = est.durationMinutes();
        distanceKm = est.distanceKm();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", activeOrderId.toString());
        payload.put("rider_id", riderId.toString());
        payload.put("lat", etaSource.lat());
        payload.put("lng", etaSource.lng());
        payload.put("heading", etaSource.heading());
        payload.put("speed_kmh", etaSource.speed());
        payload.put("eta_minutes", etaMinutes);
        payload.put("distance_remaining_km", distanceKm);
        payload.put("last_updated_at", etaSource.timestamp().toString());
        // Defer SSE fan-out until after DB commit (no sync side-effect in txn).
        publishAfterCommit(activeOrderId, payload);
      }
    }

    maybeDetectBreach(rider, activeOrderId, latestPoint, now);

    Map<String, Object> latestPos = new LinkedHashMap<>();
    latestPos.put("lat", latestPoint.lat());
    latestPos.put("lng", latestPoint.lng());
    latestPos.put("stored_at", latestPoint.timestamp().toString());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("points_received", points.size());
    data.put("points_stored", rows.size());
    data.put("points_flagged_low_accuracy", lowAccuracyCount);
    data.put("latest_position", latestPos);
    if (etaMinutes != null) {
      data.put("eta_minutes", etaMinutes);
      data.put("distance_remaining_km", distanceKm);
    }
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> customerRiderLocation(MedmatePrincipal principal, UUID orderId) {
    requireCustomer(principal);
    OrderLocationContext order =
        customerOrders
            .findById(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    if (!principal.subject().equals(order.customerId())) {
      throw new AppException("NOT_YOUR_ORDER", "Order belongs to a different customer", 403);
    }
    if (!"OUT_FOR_DELIVERY".equals(order.status())) {
      throw new AppException("LOCATION_NOT_AVAILABLE", "Order not in OUT_FOR_DELIVERY state", 422);
    }
    if (order.riderId() == null) {
      throw new AppException("LOCATION_NOT_AVAILABLE", "Order not in OUT_FOR_DELIVERY state", 422);
    }

    Instant now = clock.instant();
    LiveLocation live =
        locationCache
            .get(order.riderId())
            .orElseGet(
                () -> locations.findLatestByRider(order.riderId()).map(this::toLive).orElse(null));
    if (live == null) {
      throw new AppException("LOCATION_NOT_AVAILABLE", "Rider location not available yet", 422);
    }

    Integer eta = null;
    Double dist = null;
    if (!liveLowAccuracy(live) && canComputeEta(order)) {
      RouteEstimate est =
          distance.estimateDriving(
              live.lat(), live.lng(), order.deliveryLat(), order.deliveryLng());
      eta = est.durationMinutes();
      dist = est.distanceKm();
    }

    boolean stale = RiderAvailability.isStaleGps(live.updatedAt(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", orderId.toString());
    data.put("rider_id", order.riderId().toString());
    data.put("rider_name", order.riderName());
    data.put("lat", live.lat());
    data.put("lng", live.lng());
    data.put("heading", live.heading());
    data.put("speed_kmh", live.speedKmh());
    data.put("last_updated_at", live.updatedAt().toString());
    data.put("eta_minutes", eta);
    data.put("distance_remaining_km", dist);
    data.put("websocket_channel", push.channelUrl(orderId));
    data.put("is_stale", stale);
    if (stale) {
      data.put("stale_code", "RIDER_LOCATION_STALE");
    }
    return data;
  }

  public SseEmitter subscribeCustomerStream(MedmatePrincipal principal, UUID orderId) {
    // auth + ownership gates reuse GET path
    customerRiderLocation(principal, orderId);
    return push.subscribe(orderId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> adminLiveLocation(MedmatePrincipal principal, UUID riderId) {
    requireOps(principal);
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    Instant now = clock.instant();
    Optional<LiveLocation> cached = locationCache.get(riderId);
    LiveLocation live =
        cached.orElseGet(() -> locations.findLatestByRider(riderId).map(this::toLive).orElse(null));

    UUID zoneId = rider.primaryZoneId();
    var active = deliveries.findActiveByRider(riderId);
    UUID activeOrderId = active.map(a -> a.orderId()).orElse(null);
    String display = RiderAvailability.displayStatus(rider.status(), active.isPresent());

    boolean inZone = true;
    if (live != null && zoneId != null) {
      if (geofences.existsForZone(zoneId)) {
        inZone = geofences.containsPoint(zoneId, live.lat(), live.lng());
      }
    }

    boolean stale = live == null || RiderAvailability.isStaleGps(live.updatedAt(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("name", rider.name());
    data.put("status", display);
    data.put("lat", live == null ? null : live.lat());
    data.put("lng", live == null ? null : live.lng());
    data.put("heading", live == null ? null : live.heading());
    data.put("speed_kmh", live == null ? null : live.speedKmh());
    data.put("accuracy_m", live == null ? null : live.accuracyM());
    data.put("last_updated_at", live == null ? null : live.updatedAt().toString());
    data.put("is_stale", stale);
    data.put("zone_id", zoneId == null ? null : zoneId.toString());
    data.put("is_in_zone", inZone);
    data.put("active_order_id", activeOrderId == null ? null : activeOrderId.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> adminLocationHistory(
      MedmatePrincipal principal, UUID riderId, UUID orderId) {
    requireOps(principal);
    if (!riders.findById(riderId).isPresent()) {
      throw new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404);
    }
    if (orderId == null) {
      throw new AppException("ORDER_ID_REQUIRED", "order_id query param missing", 422);
    }

    Instant now = clock.instant();
    Instant retentionCutoff = now.minus(HISTORY_RETENTION);
    List<LocationPoint> trail = locations.findByRiderAndOrder(riderId, orderId);
    if (trail.isEmpty()) {
      var assignment = assignments.findLatestByOrderAndRider(orderId, riderId);
      if (assignment.isPresent() && assignment.get().createdAt().isBefore(retentionCutoff)) {
        throw new AppException("HISTORY_EXPIRED", "Location history older than 30 days", 410);
      }
      Map<String, Object> empty = new LinkedHashMap<>();
      empty.put("rider_id", riderId.toString());
      empty.put("order_id", orderId.toString());
      empty.put("total_points", 0);
      empty.put("distance_km", 0.0);
      empty.put("points", List.of());
      return empty;
    }

    if (trail.get(0).recordedAt().isBefore(retentionCutoff)) {
      throw new AppException("HISTORY_EXPIRED", "Location history older than 30 days", 410);
    }

    double distanceKm = trailDistanceKm(trail);
    List<Map<String, Object>> pointMaps = new ArrayList<>(trail.size());
    for (LocationPoint p : trail) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("lat", p.lat());
      m.put("lng", p.lng());
      m.put("accuracy", p.accuracyM());
      m.put("speed_kmh", p.speedKmh());
      m.put("heading", p.heading());
      m.put("low_accuracy", p.lowAccuracy());
      m.put("timestamp", p.recordedAt().toString());
      pointMaps.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("order_id", orderId.toString());
    data.put("total_points", trail.size());
    data.put("distance_km", Math.round(distanceKm * 10.0) / 10.0);
    data.put("points", pointMaps);
    return data;
  }

  @Transactional
  public Map<String, Object> createGeofence(
      MedmatePrincipal principal, UUID zoneId, List<List<Double>> coordinates) {
    requireOps(principal);
    if (zoneId == null) {
      throw new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404);
    }
    var zone =
        zones
            .findById(zoneId)
            .orElseThrow(() -> new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404));
    if (geofences.existsForZone(zoneId)) {
      throw new AppException(
          "GEOFENCE_ALREADY_EXISTS", "Zone already has a geofence; use PATCH to update", 409);
    }
    List<double[]> ring = toRing(coordinates);
    if (!GeofencePolygons.isValidClosed(ring)) {
      throw new AppException("INVALID_POLYGON", "Polygon not closed or has < 3 points", 422);
    }

    Instant now = clock.instant();
    UUID id = Ids.newId();
    String wkt = GeofencePolygons.toWkt(ring);
    BigDecimal area =
        BigDecimal.valueOf(GeofencePolygons.approxAreaSqKm(ring)).setScale(3, RoundingMode.HALF_UP);
    String json;
    try {
      json = mapper.writeValueAsString(coordinates);
    } catch (Exception e) {
      throw new AppException("INVALID_POLYGON", "Polygon not closed or has < 3 points", 422);
    }
    geofences.insert(id, zoneId, wkt, json, area, principal.subject(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("geofence_id", id.toString());
    data.put("zone_id", zoneId.toString());
    data.put("zone_name", zone.name());
    data.put("polygon_coordinates", coordinates);
    data.put("area_sq_km", area);
    data.put("created_at", now.toString());
    return data;
  }

  int purgeOlderThan30Days() {
    Instant cutoff = clock.instant().minus(HISTORY_RETENTION);
    return locations.purgeOlderThan(cutoff);
  }

  private LiveLocation toLive(LocationPoint p) {
    return new LiveLocation(
        p.lat().doubleValue(),
        p.lng().doubleValue(),
        p.heading() == null ? null : p.heading().doubleValue(),
        p.speedKmh() == null ? null : p.speedKmh().doubleValue(),
        p.accuracyM() == null ? null : p.accuracyM().doubleValue(),
        p.orderId(),
        p.recordedAt());
  }

  private static boolean canComputeEta(OrderLocationContext order) {
    if (!"OUT_FOR_DELIVERY".equals(order.status())) {
      return false;
    }
    if (order.deliveryLat() == null) {
      return false;
    }
    return order.deliveryLng() != null;
  }

  private void maybeDetectBreach(RiderRecord rider, UUID orderId, GpsPoint latest, Instant now) {
    UUID zoneId = rider.primaryZoneId();
    if (zoneId == null) {
      return;
    }
    if (!geofences.existsForZone(zoneId)) {
      return;
    }
    boolean inside = geofences.containsPoint(zoneId, latest.lat(), latest.lng());
    if (inside) {
      return;
    }
    Instant since = now.minus(BREACH_DEBOUNCE);
    if (breaches.existsSince(rider.id(), zoneId, since)) {
      return;
    }
    UUID breachId = Ids.newId();
    breaches.insert(
        new BreachRecord(
            breachId,
            rider.id(),
            zoneId,
            orderId,
            bd(latest.lat(), 7),
            bd(latest.lng(), 7),
            true,
            now));
    outbox.publish(
        DomainEvent.of(
            "rider.geofence.breach",
            "rider",
            rider.id(),
            Map.of(
                "breach_id",
                breachId.toString(),
                "rider_id",
                rider.id().toString(),
                "zone_id",
                zoneId.toString(),
                "order_id",
                orderId == null ? "" : orderId.toString(),
                "lat",
                latest.lat(),
                "lng",
                latest.lng(),
                "template",
                "GEOFENCE_BREACH_ALERT",
                "channels",
                List.of("PUSH", "EMAIL"))));
  }

  private void publishAfterCommit(UUID orderId, Map<String, Object> payload) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      push.publish(orderId, payload);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            push.publish(orderId, payload);
          }
        });
  }

  private static boolean liveLowAccuracy(LiveLocation live) {
    return live.accuracyM() != null && live.accuracyM() > ACCURACY_LIMIT_M;
  }

  private static double trailDistanceKm(List<LocationPoint> trail) {
    double sum = 0;
    for (int i = 1; i < trail.size(); i++) {
      LocationPoint a = trail.get(i - 1);
      LocationPoint b = trail.get(i);
      sum +=
          haversine(
              a.lat().doubleValue(),
              a.lng().doubleValue(),
              b.lat().doubleValue(),
              b.lng().doubleValue());
    }
    return sum;
  }

  private static double haversine(double lat1, double lng1, double lat2, double lng2) {
    double r = 6371.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * r * Math.asin(Math.sqrt(a));
  }

  private static List<double[]> toRing(List<List<Double>> coordinates) {
    if (coordinates == null) {
      return List.of();
    }
    if (coordinates.isEmpty()) {
      return List.of();
    }
    List<double[]> ring = new ArrayList<>(coordinates.size());
    for (List<Double> c : coordinates) {
      if (c == null) {
        return List.of();
      }
      if (c.size() < 2) {
        return List.of();
      }
      Double lat = c.get(0);
      Double lng = c.get(1);
      if (lat == null) {
        return List.of();
      }
      if (lng == null) {
        return List.of();
      }
      ring.add(new double[] {lat, lng});
    }
    return ring;
  }

  private static BigDecimal bd(double v, int scale) {
    return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
  }

  private static void requireRider(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Rider role required", 403);
    }
    if (principal.role() != AuthRole.RIDER) {
      throw new AppException("FORBIDDEN", "Rider role required", 403);
    }
  }

  private static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Customer role required", 403);
    }
    if (principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("FORBIDDEN", "Customer role required", 403);
    }
  }

  private static void requireOps(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    if (!OPS.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  public record GpsPoint(
      Double lat, Double lng, Double accuracy, Double speed, Double heading, Instant timestamp) {}
}
