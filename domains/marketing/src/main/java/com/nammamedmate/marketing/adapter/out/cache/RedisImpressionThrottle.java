package com.nammamedmate.marketing.adapter.out.cache;

import com.nammamedmate.marketing.application.port.out.ImpressionThrottlePort;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis SET NX EX 30m for impression throttle; falls back to ConcurrentHashMap when Redis is
 * unavailable (unit tests).
 */
@Component
public class RedisImpressionThrottle implements ImpressionThrottlePort {

  static final Duration WINDOW = Duration.ofMinutes(30);
  static final String KEY_PREFIX = "banner:imp:";

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, Long> local = new ConcurrentHashMap<>();

  public RedisImpressionThrottle(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public boolean tryAcquire(UUID bannerId, UUID customerId, String sessionId) {
    if (bannerId == null || customerId == null) {
      return false;
    }
    String sid = sessionId == null || sessionId.isBlank() ? "anon" : sessionId.trim();
    String key = KEY_PREFIX + bannerId + ':' + customerId + ':' + sid;
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template != null) {
      try {
        Boolean ok = template.opsForValue().setIfAbsent(key, "1", WINDOW);
        return Boolean.TRUE.equals(ok);
      } catch (RuntimeException ignored) {
        // fall through to in-memory
      }
    }
    long now = System.currentTimeMillis();
    boolean[] acquired = {false};
    local.compute(
        key,
        (k, existing) -> {
          if (existing != null && now - existing < WINDOW.toMillis()) {
            return existing;
          }
          acquired[0] = true;
          return now;
        });
    return acquired[0];
  }
}
