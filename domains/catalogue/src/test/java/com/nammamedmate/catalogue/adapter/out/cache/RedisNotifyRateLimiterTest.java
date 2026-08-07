package com.nammamedmate.catalogue.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisNotifyRateLimiterTest {

  @Test
  void localFallbackAcquireAndBlock() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisNotifyRateLimiter limiter = new RedisNotifyRateLimiter(provider);
    UUID med = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    assertThat(limiter.tryAcquire(med, Duration.ofHours(4), now)).isEmpty();
    assertThat(limiter.tryAcquire(med, Duration.ofHours(4), now))
        .contains(now.plus(Duration.ofHours(4)));
    assertThat(limiter.tryAcquire(null, Duration.ofHours(4), now)).isEmpty();
  }

  @Test
  void redisAcquireAndBlockedWithTtl() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    UUID med = UUID.fromString("11111111-1111-4111-8111-111111111111");
    when(ops.setIfAbsent(eq(RedisNotifyRateLimiter.key(med)), eq("1"), any(Duration.class)))
        .thenReturn(true)
        .thenReturn(false);
    when(redis.getExpire(RedisNotifyRateLimiter.key(med))).thenReturn(100L);

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisNotifyRateLimiter limiter = new RedisNotifyRateLimiter(provider);
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    assertThat(limiter.tryAcquire(med, Duration.ofHours(4), now)).isEmpty();
    assertThat(limiter.tryAcquire(med, Duration.ofHours(4), now)).contains(now.plusSeconds(100));
  }

  @Test
  void redisBlockedWithoutTtlUsesWindow() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(false);
    when(redis.getExpire(any())).thenReturn(null).thenReturn(0L);

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisNotifyRateLimiter limiter = new RedisNotifyRateLimiter(provider);
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    assertThat(limiter.tryAcquire(UUID.randomUUID(), Duration.ofHours(4), now))
        .contains(now.plus(Duration.ofHours(4)));
    assertThat(limiter.tryAcquire(UUID.randomUUID(), Duration.ofHours(4), now))
        .contains(now.plus(Duration.ofHours(4)));
  }

  @Test
  void nullProviderAndExpiredLocal() {
    RedisNotifyRateLimiter limiter = new RedisNotifyRateLimiter(null);
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    UUID med = UUID.randomUUID();
    assertThat(limiter.tryAcquire(med, Duration.ofMillis(1), now)).isEmpty();
    assertThat(limiter.tryAcquire(med, Duration.ofHours(1), now.plusSeconds(10))).isEmpty();
  }
}
