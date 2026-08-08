package com.nammamedmate.order.adapter.out.cache;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisDeliveryOtpCacheTest {

  @Test
  @SuppressWarnings("unchecked")
  void storeWritesTtlKeyAndIgnoresBlank() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    RedisDeliveryOtpCache cache = new RedisDeliveryOtpCache(redis);
    UUID orderId = UUID.randomUUID();
    cache.store(orderId, "1234");
    cache.store(null, "1234");
    cache.store(orderId, null);
    cache.store(orderId, "  ");
    verify(ops, times(1))
        .set(eq(RedisDeliveryOtpCache.KEY_PREFIX + orderId), eq("1234"), eq(Duration.ofHours(24)));
  }
}
