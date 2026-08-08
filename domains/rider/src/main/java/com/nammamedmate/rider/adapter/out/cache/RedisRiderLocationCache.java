package com.nammamedmate.rider.adapter.out.cache;

import com.nammamedmate.rider.application.port.out.RiderLocationCachePort;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis hash `rider_location:{id}`; in-process fallback when Redis absent. */
public class RedisRiderLocationCache implements RiderLocationCachePort {

  private static final String KEY_PREFIX = "rider_location:";

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, Timed> local = new ConcurrentHashMap<>();

  public RedisRiderLocationCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public void put(UUID riderId, LiveLocation location, Duration ttl) {
    String key = KEY_PREFIX + riderId;
    Map<String, String> hash = toHash(location);
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForHash().putAll(key, hash);
      template.expire(key, ttl);
      return;
    }
    local.put(key, new Timed(location, System.currentTimeMillis() + ttl.toMillis()));
  }

  @Override
  public Optional<LiveLocation> get(UUID riderId) {
    String key = KEY_PREFIX + riderId;
    StringRedisTemplate template = template();
    if (template != null) {
      Map<Object, Object> raw = template.opsForHash().entries(key);
      if (raw.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(fromHash(raw));
    }
    Timed t = local.get(key);
    if (t == null || t.expiresAtMillis() < System.currentTimeMillis()) {
      local.remove(key);
      return Optional.empty();
    }
    return Optional.of(t.location());
  }

  @Override
  public void evict(UUID riderId) {
    String key = KEY_PREFIX + riderId;
    StringRedisTemplate template = template();
    if (template != null) {
      template.delete(key);
      return;
    }
    local.remove(key);
  }

  /** Test helper. */
  public void putLocalForTest(UUID riderId, LiveLocation location, long expiresAtMillis) {
    local.put(KEY_PREFIX + riderId, new Timed(location, expiresAtMillis));
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }

  private static Map<String, String> toHash(LiveLocation loc) {
    Map<String, String> m = new HashMap<>();
    m.put("lat", Double.toString(loc.lat()));
    m.put("lng", Double.toString(loc.lng()));
    m.put("heading", loc.heading() == null ? "" : Double.toString(loc.heading()));
    m.put("speed_kmh", loc.speedKmh() == null ? "" : Double.toString(loc.speedKmh()));
    m.put("accuracy_m", loc.accuracyM() == null ? "" : Double.toString(loc.accuracyM()));
    m.put("order_id", loc.orderId() == null ? "" : loc.orderId().toString());
    m.put("updated_at", loc.updatedAt().toString());
    return m;
  }

  private static LiveLocation fromHash(Map<Object, Object> raw) {
    double lat = Double.parseDouble(str(raw.get("lat")));
    double lng = Double.parseDouble(str(raw.get("lng")));
    Double heading = parseOptDouble(str(raw.get("heading")));
    Double speed = parseOptDouble(str(raw.get("speed_kmh")));
    Double accuracy = parseOptDouble(str(raw.get("accuracy_m")));
    String orderRaw = str(raw.get("order_id"));
    UUID orderId = orderRaw.isBlank() ? null : UUID.fromString(orderRaw);
    Instant updated = Instant.parse(str(raw.get("updated_at")));
    return new LiveLocation(lat, lng, heading, speed, accuracy, orderId, updated);
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }

  private static Double parseOptDouble(String s) {
    if (s.isBlank()) {
      return null;
    }
    return Double.parseDouble(s);
  }

  private record Timed(LiveLocation location, long expiresAtMillis) {}
}
