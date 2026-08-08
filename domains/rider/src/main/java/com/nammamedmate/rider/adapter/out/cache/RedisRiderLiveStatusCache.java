package com.nammamedmate.rider.adapter.out.cache;

import com.nammamedmate.rider.application.port.out.RiderLiveStatusCachePort;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis live rider status; in-process fallback when Redis absent. */
public class RedisRiderLiveStatusCache implements RiderLiveStatusCachePort {

  private static final String KEY_PREFIX = "rider:live:";

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, CacheEntry> local = new ConcurrentHashMap<>();

  public RedisRiderLiveStatusCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public void put(UUID riderId, String status, Duration ttl) {
    String key = KEY_PREFIX + riderId;
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template != null) {
      template.opsForValue().set(key, status, ttl);
      return;
    }
    local.put(key, new CacheEntry(status, System.currentTimeMillis() + ttl.toMillis()));
  }

  @Override
  public Optional<String> get(UUID riderId) {
    String key = KEY_PREFIX + riderId;
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template != null) {
      return Optional.ofNullable(template.opsForValue().get(key));
    }
    CacheEntry entry = local.get(key);
    if (entry == null || entry.expiresAtMillis() < System.currentTimeMillis()) {
      local.remove(key);
      return Optional.empty();
    }
    return Optional.of(entry.value());
  }

  @Override
  public void evict(UUID riderId) {
    String key = KEY_PREFIX + riderId;
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template != null) {
      template.delete(key);
      return;
    }
    local.remove(key);
  }

  private record CacheEntry(String value, long expiresAtMillis) {}
}
