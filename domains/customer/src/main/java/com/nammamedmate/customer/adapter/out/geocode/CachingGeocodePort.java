package com.nammamedmate.customer.adapter.out.geocode;

import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Caches reverse-geocode results keyed on lat,lng rounded to 4 decimal places (1-hour TTL). Falls
 * back to an in-memory map when Redis is absent.
 */
public final class CachingGeocodePort implements GeocodePort {

  private static final String KEY_PREFIX = "geocode:";
  private static final Duration TTL = Duration.ofHours(1);

  private final GeocodePort delegate;
  private final StringRedisTemplate redis;
  private final ConcurrentHashMap<String, SuggestedAddress> local = new ConcurrentHashMap<>();

  public CachingGeocodePort(GeocodePort delegate, ObjectProvider<StringRedisTemplate> redis) {
    this.delegate = delegate;
    this.redis = redis == null ? null : redis.getIfAvailable();
  }

  public CachingGeocodePort(GeocodePort delegate) {
    this(delegate, null);
  }

  @Override
  public SuggestedAddress reverseGeocode(double latitude, double longitude) {
    String key = cacheKey(latitude, longitude);
    SuggestedAddress cached = getCached(key);
    if (cached != null) {
      return cached;
    }
    SuggestedAddress result = delegate.reverseGeocode(latitude, longitude);
    putCached(key, result);
    return result;
  }

  private SuggestedAddress getCached(String key) {
    if (redis != null) {
      String raw = redis.opsForValue().get(KEY_PREFIX + key);
      if (raw == null) {
        return null;
      }
      return decode(raw);
    }
    return local.get(key);
  }

  private void putCached(String key, SuggestedAddress address) {
    if (redis != null) {
      redis.opsForValue().set(KEY_PREFIX + key, encode(address), TTL);
      return;
    }
    local.put(key, address);
  }

  static String cacheKey(double latitude, double longitude) {
    BigDecimal lat = BigDecimal.valueOf(latitude).setScale(4, RoundingMode.HALF_UP);
    BigDecimal lng = BigDecimal.valueOf(longitude).setScale(4, RoundingMode.HALF_UP);
    return lat.toPlainString() + "," + lng.toPlainString();
  }

  static String encode(SuggestedAddress a) {
    return String.join(
        "\n",
        nullToEmpty(a.flatBuilding()),
        nullToEmpty(a.areaLocality()),
        nullToEmpty(a.city()),
        nullToEmpty(a.state()),
        nullToEmpty(a.pincode()),
        nullToEmpty(a.formattedAddress()),
        Double.toString(a.latitude()),
        Double.toString(a.longitude()));
  }

  static SuggestedAddress decode(String raw) {
    String[] parts = raw.split("\n", -1);
    if (parts.length < 8) {
      throw new AppException("GEOCODE_SERVICE_ERROR", "Corrupt geocode cache entry", 502);
    }
    return new SuggestedAddress(
        parts[0],
        parts[1],
        parts[2],
        parts[3],
        parts[4],
        parts[5],
        Double.parseDouble(parts[6]),
        Double.parseDouble(parts[7]));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
