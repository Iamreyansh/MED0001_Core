package com.nammamedmate.integration.application;

import com.nammamedmate.integration.application.port.out.GeocodeCacheStore;
import com.nammamedmate.integration.application.port.out.IntegrationEventPort;
import com.nammamedmate.integration.application.port.out.MapsApiCallLogStore;
import com.nammamedmate.integration.application.port.out.MapsClientPort;
import com.nammamedmate.integration.application.port.out.MapsClientPort.DirectionStep;
import com.nammamedmate.integration.application.port.out.MapsClientPort.DirectionsResult;
import com.nammamedmate.integration.application.port.out.MapsClientPort.GeocodeResult;
import com.nammamedmate.integration.application.port.out.MapsClientPort.LatLng;
import com.nammamedmate.integration.application.port.out.MapsClientPort.MatrixCell;
import com.nammamedmate.integration.application.port.out.MapsClientPort.ReverseGeocodeResult;
import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import com.nammamedmate.integration.domain.MapsApiCallLog;
import com.nammamedmate.integration.domain.MapsApiTypes;
import com.nammamedmate.integration.domain.PointInPolygon;
import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapsService {

  private static final Logger log = LoggerFactory.getLogger(MapsService.class);

  private static final int MAX_ORIGINS = 25;
  private static final int MAX_DESTINATIONS = 25;
  private static final BigDecimal DAILY_BUDGET_RS = new BigDecimal("500");
  private static final BigDecimal COST_GEOCODE = new BigDecimal("0.4200");
  private static final BigDecimal COST_MATRIX_ELEMENT = new BigDecimal("0.8400");
  private static final BigDecimal COST_DIRECTIONS = new BigDecimal("0.8400");
  private static final Duration FORWARD_TTL = Duration.ofHours(24);
  private static final Duration REVERSE_TTL = Duration.ofHours(1);
  private static final String REDIS_PREFIX = "maps:geocode:";

  private final MapsClientPort client;
  private final MapsApiCallLogStore callLog;
  private final GeocodeCacheStore cacheStore;
  private final IntegrationEventPort events;
  private final Clock clock;
  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, GeocodeCacheEntry> memoryCache =
      new ConcurrentHashMap<>();
  private final AtomicBoolean budgetAlertFiredToday = new AtomicBoolean(false);
  private volatile LocalDate budgetAlertDay;

  public MapsService(
      MapsClientPort client,
      MapsApiCallLogStore callLog,
      GeocodeCacheStore cacheStore,
      IntegrationEventPort events,
      Clock clock,
      ObjectProvider<StringRedisTemplate> redis) {
    this.client = client;
    this.callLog = callLog;
    this.cacheStore = cacheStore;
    this.events = events;
    this.clock = clock;
    this.redis = redis;
  }

  private StringRedisTemplate redis() {
    return redis == null ? null : redis.getIfAvailable();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Map<String, Object> geocode(
      String address, String city, String pincode, String callingService) {
    Instant started = clock.instant();
    String query = buildAddressQuery(address, city, pincode);
    String cacheKey = normalizeAddress(query);
    Optional<GeocodeCacheEntry> cached = lookupCache(cacheKey, started);
    if (cached.isPresent()) {
      GeocodeCacheEntry hit = cached.get();
      logCall(
          MapsApiTypes.GEOCODE,
          summary("fwd", city, pincode),
          "OK",
          latency(started),
          true,
          BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
          callingService);
      return geocodeResponse(hit, true);
    }

    GeocodeResult result;
    try {
      result = client.geocode(query);
    } catch (AppException e) {
      logCall(
          MapsApiTypes.GEOCODE,
          summary("fwd", city, pincode),
          "ERROR",
          latency(started),
          false,
          BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
          callingService);
      throw e;
    }
    if ("ZERO_RESULTS".equals(result.status())) {
      logCall(
          MapsApiTypes.GEOCODE,
          summary("fwd", city, pincode),
          "ZERO_RESULTS",
          latency(started),
          false,
          COST_GEOCODE,
          callingService);
      throw new AppException(
          "GEOCODE_NO_RESULTS",
          "Google returned ZERO_RESULTS; use last known address as fallback",
          422);
    }
    Instant now = clock.instant();
    GeocodeCacheEntry entry =
        new GeocodeCacheEntry(
            cacheKey,
            result.lat(),
            result.lng(),
            result.formattedAddress(),
            result.placeId(),
            now,
            now.plus(FORWARD_TTL));
    putCache(entry);
    logCall(
        MapsApiTypes.GEOCODE,
        summary("fwd", city, pincode),
        "OK",
        latency(started),
        false,
        COST_GEOCODE,
        callingService);
    Map<String, Object> data = geocodeResponse(entry, false);
    data.put("accuracy", result.accuracy());
    return data;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Map<String, Object> reverseGeocode(double lat, double lng, String callingService) {
    Instant started = clock.instant();
    String cacheKey = "rev:" + round4(lat) + "," + round4(lng);
    Optional<GeocodeCacheEntry> cached = lookupCache(cacheKey, started);
    if (cached.isPresent()) {
      GeocodeCacheEntry hit = cached.get();
      logCall(
          MapsApiTypes.REVERSE_GEOCODE,
          "rev@" + round4(lat) + "," + round4(lng),
          "OK",
          latency(started),
          true,
          BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
          callingService);
      return reverseFromCache(hit, true);
    }

    ReverseGeocodeResult result;
    try {
      result = client.reverseGeocode(lat, lng);
    } catch (AppException e) {
      logCall(
          MapsApiTypes.REVERSE_GEOCODE,
          "rev@" + round4(lat) + "," + round4(lng),
          "ERROR",
          latency(started),
          false,
          BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
          callingService);
      throw e;
    }
    if ("ZERO_RESULTS".equals(result.status())) {
      logCall(
          MapsApiTypes.REVERSE_GEOCODE,
          "rev@" + round4(lat) + "," + round4(lng),
          "ZERO_RESULTS",
          latency(started),
          false,
          COST_GEOCODE,
          callingService);
      throw new AppException(
          "GEOCODE_NO_RESULTS",
          "Google returned ZERO_RESULTS; use last known address as fallback",
          422);
    }
    Instant now = clock.instant();
    GeocodeCacheEntry entry =
        new GeocodeCacheEntry(
            cacheKey,
            lat,
            lng,
            result.formattedAddress(),
            result.placeId(),
            now,
            now.plus(REVERSE_TTL));
    putCache(entry);
    logCall(
        MapsApiTypes.REVERSE_GEOCODE,
        "rev@" + round4(lat) + "," + round4(lng),
        "OK",
        latency(started),
        false,
        COST_GEOCODE,
        callingService);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("formatted_address", result.formattedAddress());
    data.put("area_locality", result.areaLocality());
    data.put("city", result.city());
    data.put("state", result.state());
    data.put("pincode", result.pincode());
    data.put("place_id", result.placeId());
    data.put("cache_hit", false);
    return data;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Map<String, Object> distanceMatrix(
      List<LatLng> origins, List<LatLng> destinations, String mode, String callingService) {
    Instant started = clock.instant();
    List<LatLng> o = origins == null ? List.of() : origins;
    List<LatLng> d = destinations == null ? List.of() : destinations;
    if (o.size() > MAX_ORIGINS) {
      throw new AppException(
          "TOO_MANY_ORIGINS",
          "origins must be at most " + MAX_ORIGINS + " (Google Distance Matrix limit)",
          422);
    }
    if (d.size() > MAX_DESTINATIONS) {
      throw new AppException(
          "TOO_MANY_DESTINATIONS",
          "destinations must be at most " + MAX_DESTINATIONS + " (Google Distance Matrix limit)",
          422);
    }
    String travelMode = normalizeMode(mode);
    List<MatrixCell> cells;
    try {
      cells = client.distanceMatrix(o, d, travelMode);
    } catch (AppException e) {
      logCall(
          MapsApiTypes.DISTANCE_MATRIX,
          "origins=" + o.size() + ",dests=" + d.size(),
          "ERROR",
          latency(started),
          false,
          BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
          callingService);
      throw e;
    }
    BigDecimal cost =
        COST_MATRIX_ELEMENT
            .multiply(BigDecimal.valueOf((long) o.size() * d.size()))
            .setScale(4, RoundingMode.HALF_UP);
    logCall(
        MapsApiTypes.DISTANCE_MATRIX,
        "origins=" + o.size() + ",dests=" + d.size() + ",mode=" + travelMode,
        "OK",
        latency(started),
        false,
        cost,
        callingService);
    List<Map<String, Object>> matrix = new ArrayList<>();
    for (MatrixCell cell : cells) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("origin_index", cell.originIndex());
      row.put("destination_index", cell.destinationIndex());
      row.put("distance_meters", cell.distanceMeters());
      row.put("duration_seconds", cell.durationSeconds());
      row.put("status", cell.status());
      matrix.add(row);
    }
    return Map.of("matrix", matrix);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Map<String, Object> directions(
      LatLng origin, LatLng destination, String mode, String callingService) {
    Instant started = clock.instant();
    if (origin == null || destination == null) {
      throw new AppException("VALIDATION_ERROR", "origin and destination are required", 400);
    }
    String travelMode = normalizeMode(mode);
    DirectionsResult result;
    try {
      result = client.directions(origin, destination, travelMode);
    } catch (AppException e) {
      logCall(
          MapsApiTypes.DIRECTIONS,
          "mode=" + travelMode,
          "ERROR",
          latency(started),
          false,
          BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
          callingService);
      throw e;
    }
    logCall(
        MapsApiTypes.DIRECTIONS,
        "mode=" + travelMode,
        result.status(),
        latency(started),
        false,
        COST_DIRECTIONS,
        callingService);
    List<Map<String, Object>> steps = new ArrayList<>();
    for (DirectionStep step : result.steps()) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("instruction", step.instruction());
      s.put("distance_meters", step.distanceMeters());
      s.put("duration_seconds", step.durationSeconds());
      steps.add(s);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("route_polyline", result.routePolyline());
    data.put("distance_meters", result.distanceMeters());
    data.put("duration_seconds", result.durationSeconds());
    data.put("duration_in_traffic_seconds", result.durationInTrafficSeconds());
    data.put("steps", steps);
    return data;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Map<String, Object> zoneCheck(
      double lat, double lng, double[][] polygon, String zoneId, String callingService) {
    Instant started = clock.instant();
    boolean inside = PointInPolygon.contains(lat, lng, polygon);
    logCall(
        MapsApiTypes.ZONE_CHECK,
        "zone=" + (zoneId == null ? "?" : zoneId),
        "OK",
        latency(started),
        false,
        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
        callingService);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("inside", inside);
    data.put("zone_id", zoneId);
    data.put("distance_to_boundary_meters", null);
    return data;
  }

  private void logCall(
      String apiType,
      String summary,
      String status,
      int latencyMs,
      boolean cacheHit,
      BigDecimal cost,
      String callingService) {
    Instant now = clock.instant();
    callLog.insert(
        new MapsApiCallLog(
            UUID.randomUUID(),
            apiType,
            truncate(summary, 200),
            status,
            latencyMs,
            cacheHit,
            cost,
            now,
            callingService == null || callingService.isBlank() ? "unknown" : callingService));
    maybeAlertBudget(now);
  }

  private void maybeAlertBudget(Instant now) {
    LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
    if (budgetAlertDay == null || !budgetAlertDay.equals(day)) {
      budgetAlertDay = day;
      budgetAlertFiredToday.set(false);
    }
    Instant dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
    BigDecimal spend = callLog.sumEstimatedCostSince(dayStart);
    if (spend.compareTo(DAILY_BUDGET_RS) > 0 && budgetAlertFiredToday.compareAndSet(false, true)) {
      log.warn("MAPS_BUDGET_EXCEEDED daily_spend_rs={} budget_rs={}", spend, DAILY_BUDGET_RS);
      events.publish(
          "MAPS_BUDGET_EXCEEDED",
          "maps_budget",
          UUID.nameUUIDFromBytes(("maps-budget-" + day).getBytes(StandardCharsets.UTF_8)),
          Map.of(
              "daily_spend_rs", spend,
              "budget_rs", DAILY_BUDGET_RS,
              "day", day.toString()));
    }
  }

  private Optional<GeocodeCacheEntry> lookupCache(String cacheKey, Instant now) {
    StringRedisTemplate r = redis();
    if (r != null) {
      String raw = r.opsForValue().get(REDIS_PREFIX + cacheKey);
      if (raw != null) {
        Optional<GeocodeCacheEntry> decoded = decodeRedis(raw, cacheKey);
        if (decoded.isPresent() && decoded.get().expiresAt().isAfter(now)) {
          return decoded;
        }
      }
    }
    GeocodeCacheEntry mem = memoryCache.get(cacheKey);
    if (mem != null && mem.expiresAt().isAfter(now)) {
      return Optional.of(mem);
    }
    return cacheStore.findValid(cacheKey, now);
  }

  private void putCache(GeocodeCacheEntry entry) {
    memoryCache.put(entry.cacheKey(), entry);
    cacheStore.upsert(entry);
    StringRedisTemplate r = redis();
    if (r != null) {
      long seconds = Math.max(1, Duration.between(clock.instant(), entry.expiresAt()).getSeconds());
      r.opsForValue()
          .set(REDIS_PREFIX + entry.cacheKey(), encodeRedis(entry), Duration.ofSeconds(seconds));
    }
  }

  private static Optional<GeocodeCacheEntry> decodeRedis(String raw, String cacheKey) {
    String[] parts = raw.split("\n", -1);
    if (parts.length < 6) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new GeocodeCacheEntry(
              cacheKey,
              Double.parseDouble(parts[0]),
              Double.parseDouble(parts[1]),
              parts[2],
              parts[3],
              Instant.parse(parts[4]),
              Instant.parse(parts[5])));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static String encodeRedis(GeocodeCacheEntry e) {
    return String.join(
        "\n",
        Double.toString(e.lat()),
        Double.toString(e.lng()),
        e.formattedAddress() == null ? "" : e.formattedAddress(),
        e.placeId() == null ? "" : e.placeId(),
        e.cachedAt().toString(),
        e.expiresAt().toString());
  }

  private static Map<String, Object> geocodeResponse(GeocodeCacheEntry entry, boolean cacheHit) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("lat", entry.lat());
    data.put("lng", entry.lng());
    data.put("formatted_address", entry.formattedAddress());
    data.put("place_id", entry.placeId());
    data.put("accuracy", "ROOFTOP");
    data.put("cache_hit", cacheHit);
    data.put("cached_at", cacheHit ? entry.cachedAt().toString() : null);
    return data;
  }

  private static Map<String, Object> reverseFromCache(GeocodeCacheEntry hit, boolean cacheHit) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("formatted_address", hit.formattedAddress());
    data.put("area_locality", "");
    data.put("city", "");
    data.put("state", "");
    data.put("pincode", "");
    data.put("place_id", hit.placeId());
    data.put("cache_hit", cacheHit);
    return data;
  }

  private static String buildAddressQuery(String address, String city, String pincode) {
    StringBuilder sb = new StringBuilder();
    if (address != null && !address.isBlank()) {
      sb.append(address.trim());
    }
    if (city != null && !city.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(city.trim());
    }
    if (pincode != null && !pincode.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(pincode.trim());
    }
    if (sb.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "address is required", 400);
    }
    return sb.toString();
  }

  static String normalizeAddress(String address) {
    return address.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  static String round4(double value) {
    return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  private static String normalizeMode(String mode) {
    if (mode == null || mode.isBlank()) {
      return "DRIVING";
    }
    String m = mode.trim().toUpperCase(Locale.ROOT);
    if (!"DRIVING".equals(m) && !"BICYCLING".equals(m) && !"WALKING".equals(m)) {
      return "DRIVING";
    }
    return m;
  }

  private static String summary(String kind, String city, String pincode) {
    return kind
        + " city="
        + (city == null ? "?" : city)
        + " pin="
        + (pincode == null ? "?" : pincode);
  }

  private int latency(Instant started) {
    return (int) Math.max(0, Duration.between(started, clock.instant()).toMillis());
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
