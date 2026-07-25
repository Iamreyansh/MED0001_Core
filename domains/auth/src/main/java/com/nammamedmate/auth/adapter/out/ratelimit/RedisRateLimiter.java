package com.nammamedmate.auth.adapter.out.ratelimit;

import com.nammamedmate.kernel.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisRateLimiter implements RateLimiter {

  private final StringRedisTemplate redis;

  public RedisRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public boolean tryAcquire(String key, int limit, int windowSeconds) {
    if (key == null || key.isBlank() || limit < 1 || windowSeconds < 1) {
      return false;
    }
    Long count = redis.opsForValue().increment(key);
    if (count == null) {
      return false;
    }
    if (count == 1L) {
      redis.expire(key, Duration.ofSeconds(windowSeconds));
    }
    return count <= limit;
  }

  @Override
  public int secondsUntilAvailable(String key, int limit, int windowSeconds) {
    if (key == null || key.isBlank() || limit < 1 || windowSeconds < 1) {
      return 0;
    }
    String raw = redis.opsForValue().get(key);
    if (raw == null) {
      return 0;
    }
    long count;
    try {
      count = Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      return 0;
    }
    if (count < limit) {
      return 0;
    }
    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
    if (ttl == null || ttl < 0) {
      return windowSeconds;
    }
    return ttl == 0 ? 1 : ttl.intValue();
  }

  @Override
  public void putCooldown(String key, int ttlSeconds) {
    if (key == null || key.isBlank() || ttlSeconds < 1) {
      return;
    }
    redis.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
  }

  @Override
  public int cooldownRemainingSeconds(String key) {
    if (key == null || key.isBlank()) {
      return 0;
    }
    Boolean exists = redis.hasKey(key);
    if (exists == null || !exists) {
      return 0;
    }
    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
    if (ttl == null || ttl < 0) {
      return 0;
    }
    return ttl == 0 ? 1 : ttl.intValue();
  }
}
