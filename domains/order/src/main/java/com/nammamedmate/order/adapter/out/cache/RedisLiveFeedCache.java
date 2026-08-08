package com.nammamedmate.order.adapter.out.cache;

import com.nammamedmate.order.application.port.out.LiveFeedCachePort;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 10s live-feed cache; falls back to in-process map when Redis absent. */
public class RedisLiveFeedCache implements LiveFeedCachePort {

  public static final String KEY = "admin:orders:live-feed";

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, CacheEntry> local = new ConcurrentHashMap<>();

  public RedisLiveFeedCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> get(String key) {
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
  public void put(String key, String json, Duration ttl) {
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template != null) {
      template.opsForValue().set(key, json, ttl);
      return;
    }
    local.put(key, new CacheEntry(json, System.currentTimeMillis() + ttl.toMillis()));
  }

  private record CacheEntry(String value, long expiresAtMillis) {}
}
