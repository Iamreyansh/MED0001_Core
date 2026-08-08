package com.nammamedmate.rider.adapter.out.cache;

import com.nammamedmate.rider.application.port.out.AssignmentOtpCachePort;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis OTP + concurrent counters; in-process fallback when Redis absent. */
public class RedisAssignmentOtpCache implements AssignmentOtpCachePort {

  private static final String PICKUP = "rider:pickup-otp:";
  private static final String DELIVERY = "rider:delivery-otp:";
  private static final String ATTEMPTS = "rider:pickup-otp-attempts:";
  private static final String CONCURRENT = "rider:concurrent:";
  private static final int MAX_ATTEMPTS = 5;

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, CacheEntry> local = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> localAttempts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> localConcurrent =
      new ConcurrentHashMap<>();

  public RedisAssignmentOtpCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public void storePickupOtp(UUID orderId, String otp) {
    put(PICKUP + orderId, otp, TTL);
  }

  @Override
  public void storeDeliveryOtp(UUID orderId, String otp) {
    put(DELIVERY + orderId, otp, TTL);
  }

  @Override
  public Optional<String> getPickupOtp(UUID orderId) {
    return get(PICKUP + orderId);
  }

  @Override
  public Optional<String> getDeliveryOtp(UUID orderId) {
    return get(DELIVERY + orderId);
  }

  @Override
  public void evict(UUID orderId) {
    delete(PICKUP + orderId);
    delete(DELIVERY + orderId);
    delete(ATTEMPTS + orderId);
  }

  @Override
  public int remainingPickupAttempts(UUID orderId) {
    String key = ATTEMPTS + orderId;
    StringRedisTemplate template = template();
    if (template != null) {
      String v = template.opsForValue().get(key);
      if (v == null) {
        return MAX_ATTEMPTS;
      }
      return Math.max(0, MAX_ATTEMPTS - Integer.parseInt(v));
    }
    AtomicInteger used = localAttempts.get(key);
    return used == null ? MAX_ATTEMPTS : Math.max(0, MAX_ATTEMPTS - used.get());
  }

  @Override
  public int consumePickupAttempt(UUID orderId) {
    String key = ATTEMPTS + orderId;
    StringRedisTemplate template = template();
    if (template != null) {
      Long used = template.opsForValue().increment(key);
      template.expire(key, TTL);
      int u = used == null ? 1 : used.intValue();
      return Math.max(0, MAX_ATTEMPTS - u);
    }
    int u = localAttempts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    return Math.max(0, MAX_ATTEMPTS - u);
  }

  @Override
  public void resetPickupAttempts(UUID orderId) {
    delete(ATTEMPTS + orderId);
    localAttempts.remove(ATTEMPTS + orderId);
  }

  @Override
  public int getConcurrent(UUID riderId) {
    String key = CONCURRENT + riderId;
    StringRedisTemplate template = template();
    if (template != null) {
      String v = template.opsForValue().get(key);
      return v == null ? 0 : Integer.parseInt(v);
    }
    AtomicInteger n = localConcurrent.get(key);
    return n == null ? 0 : n.get();
  }

  @Override
  public void setConcurrent(UUID riderId, int value) {
    String key = CONCURRENT + riderId;
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().set(key, String.valueOf(Math.max(0, value)));
      return;
    }
    localConcurrent.put(key, new AtomicInteger(Math.max(0, value)));
  }

  @Override
  public void incrConcurrent(UUID riderId) {
    String key = CONCURRENT + riderId;
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().increment(key);
      return;
    }
    localConcurrent.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
  }

  @Override
  public void decrConcurrent(UUID riderId) {
    String key = CONCURRENT + riderId;
    StringRedisTemplate template = template();
    if (template != null) {
      Long v = template.opsForValue().decrement(key);
      if (v == null) {
        return;
      }
      if (v < 0) {
        template.opsForValue().set(key, "0");
      }
      return;
    }
    AtomicInteger n = localConcurrent.computeIfAbsent(key, k -> new AtomicInteger(0));
    int next = n.decrementAndGet();
    if (next < 0) {
      n.set(0);
    }
  }

  private void put(String key, String value, Duration ttl) {
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().set(key, value, ttl);
      return;
    }
    local.put(key, new CacheEntry(value, System.currentTimeMillis() + ttl.toMillis()));
  }

  private Optional<String> get(String key) {
    StringRedisTemplate template = template();
    if (template != null) {
      return Optional.ofNullable(template.opsForValue().get(key));
    }
    CacheEntry e = local.get(key);
    if (e == null) {
      return Optional.empty();
    }
    if (e.expiresAtMillis() < System.currentTimeMillis()) {
      local.remove(key);
      return Optional.empty();
    }
    return Optional.of(e.value());
  }

  /** Test helper: seed a local cache entry with custom expiry. */
  public void putLocalForTest(String key, String value, long expiresAtMillis) {
    local.put(key, new CacheEntry(value, expiresAtMillis));
  }

  private void delete(String key) {
    StringRedisTemplate template = template();
    if (template != null) {
      template.delete(key);
      return;
    }
    local.remove(key);
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }

  private record CacheEntry(String value, long expiresAtMillis) {}
}
