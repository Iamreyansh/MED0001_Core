package com.nammamedmate.auth.adapter.out.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRateLimiterTest {

  private StringRedisTemplate redis;
  private ValueOperations<String, String> values;
  private RedisRateLimiter limiter;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    limiter = new RedisRateLimiter(redis);
  }

  @Test
  void tryAcquireIncrementsAndSetsExpiryOnFirstOnly() {
    when(values.increment("k")).thenReturn(1L);
    assertThat(limiter.tryAcquire("k", 3, 60)).isTrue();
    verify(redis).expire(eq("k"), eq(Duration.ofSeconds(60)));

    when(values.increment("k")).thenReturn(2L);
    assertThat(limiter.tryAcquire("k", 3, 60)).isTrue();
  }

  @Test
  void tryAcquireRejectsOverLimitAndInvalidArgs() {
    when(values.increment("k")).thenReturn(4L);
    assertThat(limiter.tryAcquire("k", 3, 60)).isFalse();
    assertThat(limiter.tryAcquire(null, 3, 60)).isFalse();
    assertThat(limiter.tryAcquire("", 3, 60)).isFalse();
    assertThat(limiter.tryAcquire(" ", 3, 60)).isFalse();
    assertThat(limiter.tryAcquire("k", 0, 60)).isFalse();
    assertThat(limiter.tryAcquire("k", -1, 60)).isFalse();
    assertThat(limiter.tryAcquire("k", 3, 0)).isFalse();
    assertThat(limiter.tryAcquire("k", 3, -1)).isFalse();
    when(values.increment("n")).thenReturn(null);
    assertThat(limiter.tryAcquire("n", 3, 60)).isFalse();
  }

  @Test
  void secondsUntilAvailableUsesTtl() {
    assertThat(limiter.secondsUntilAvailable(null, 1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("", 1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable(" ", 1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", 0, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", -1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", 1, 0)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", 1, -1)).isZero();
    when(values.get("k")).thenReturn(null);
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isZero();
    when(values.get("k")).thenReturn("1");
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isZero();
    when(values.get("k")).thenReturn("x");
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isZero();
    when(values.get("k")).thenReturn("3");
    when(redis.getExpire("k", TimeUnit.SECONDS)).thenReturn(12L);
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isEqualTo(12);
    when(redis.getExpire("k", TimeUnit.SECONDS)).thenReturn(null);
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isEqualTo(60);
    when(redis.getExpire("k", TimeUnit.SECONDS)).thenReturn(-1L);
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isEqualTo(60);
    when(redis.getExpire("k", TimeUnit.SECONDS)).thenReturn(0L);
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isEqualTo(1);
  }

  @Test
  void cooldownHelpers() {
    limiter.putCooldown(null, 10);
    limiter.putCooldown("", 10);
    limiter.putCooldown(" ", 10);
    limiter.putCooldown("c", 0);
    limiter.putCooldown("c", -1);
    verify(values, never()).set(eq("c"), eq("1"), any(Duration.class));
    limiter.putCooldown("c", 30);
    verify(values).set(eq("c"), eq("1"), any(Duration.class));

    assertThat(limiter.cooldownRemainingSeconds(null)).isZero();
    assertThat(limiter.cooldownRemainingSeconds("")).isZero();
    assertThat(limiter.cooldownRemainingSeconds(" ")).isZero();
    when(redis.hasKey("c")).thenReturn(null);
    assertThat(limiter.cooldownRemainingSeconds("c")).isZero();
    when(redis.hasKey("c")).thenReturn(false);
    assertThat(limiter.cooldownRemainingSeconds("c")).isZero();
    when(redis.hasKey("c")).thenReturn(true);
    when(redis.getExpire("c", TimeUnit.SECONDS)).thenReturn(5L);
    assertThat(limiter.cooldownRemainingSeconds("c")).isEqualTo(5);
    when(redis.getExpire("c", TimeUnit.SECONDS)).thenReturn(null);
    assertThat(limiter.cooldownRemainingSeconds("c")).isZero();
    when(redis.getExpire("c", TimeUnit.SECONDS)).thenReturn(-1L);
    assertThat(limiter.cooldownRemainingSeconds("c")).isZero();
    when(redis.getExpire("c", TimeUnit.SECONDS)).thenReturn(0L);
    assertThat(limiter.cooldownRemainingSeconds("c")).isEqualTo(1);
  }
}
