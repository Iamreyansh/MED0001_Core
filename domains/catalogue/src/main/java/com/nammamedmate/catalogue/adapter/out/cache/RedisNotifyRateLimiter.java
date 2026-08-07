package com.nammamedmate.catalogue.adapter.out.cache;

import com.nammamedmate.catalogue.application.port.out.NotifyRateLimitPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisNotifyRateLimiter implements NotifyRateLimitPort {

  private final ObjectProvider<StringRedisTemplate> redis;
  private final Map<String, Instant> local = new ConcurrentHashMap<>();

  public RedisNotifyRateLimiter(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public Optional<Instant> tryAcquire(UUID medicineId, Duration window, Instant now) {
    String key = key(medicineId);
    StringRedisTemplate template = template();
    if (template != null) {
      Boolean ok = template.opsForValue().setIfAbsent(key, "1", window);
      if (Boolean.TRUE.equals(ok)) {
        return Optional.empty();
      }
      Long ttl = template.getExpire(key);
      if (ttl != null && ttl > 0) {
        return Optional.of(now.plusSeconds(ttl));
      }
      return Optional.of(now.plus(window));
    }
    Instant expires = local.get(key);
    if (expires != null && expires.isAfter(now)) {
      return Optional.of(expires);
    }
    local.put(key, now.plus(window));
    return Optional.empty();
  }

  static String key(UUID medicineId) {
    return "catalogue:price-ceiling-notify:" + (medicineId == null ? "ALL" : medicineId);
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }
}
