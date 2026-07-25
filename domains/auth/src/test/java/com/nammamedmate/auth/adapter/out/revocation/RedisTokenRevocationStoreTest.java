package com.nammamedmate.auth.adapter.out.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisTokenRevocationStoreTest {

  private StringRedisTemplate redis;
  private ValueOperations<String, String> values;
  private RedisTokenRevocationStore store;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    store = new RedisTokenRevocationStore(redis);
  }

  @Test
  void isRevokedUsesHasKey() {
    assertThat(store.isRevoked(null)).isTrue();
    assertThat(store.isRevoked(" ")).isTrue();
    when(redis.hasKey("auth:revoked:jti")).thenReturn(true);
    assertThat(store.isRevoked("jti")).isTrue();
    when(redis.hasKey("auth:revoked:other")).thenReturn(false);
    assertThat(store.isRevoked("other")).isFalse();
    when(redis.hasKey("auth:revoked:nullish")).thenReturn(null);
    assertThat(store.isRevoked("nullish")).isFalse();
  }

  @Test
  void revokeSetsKeyWithTtl() {
    store.revoke(null, 10);
    store.revoke(" ", 10);
    store.revoke("jti", 0);
    verify(values, org.mockito.Mockito.never()).set(any(), any(), any(Duration.class));

    store.revoke("jti", 300);
    verify(values).set(eq("auth:revoked:jti"), eq("1"), eq(Duration.ofSeconds(300)));
  }

  @Test
  void tryRevokeUsesSetIfAbsent() {
    assertThat(store.tryRevoke(null, 10)).isFalse();
    assertThat(store.tryRevoke("", 10)).isFalse();
    assertThat(store.tryRevoke("jti", 0)).isFalse();

    when(values.setIfAbsent(eq("auth:revoked:jti"), eq("1"), eq(Duration.ofSeconds(60))))
        .thenReturn(true);
    assertThat(store.tryRevoke("jti", 60)).isTrue();

    when(values.setIfAbsent(eq("auth:revoked:jti"), eq("1"), eq(Duration.ofSeconds(60))))
        .thenReturn(false);
    assertThat(store.tryRevoke("jti", 60)).isFalse();

    when(values.setIfAbsent(eq("auth:revoked:x"), eq("1"), eq(Duration.ofSeconds(60))))
        .thenReturn(null);
    assertThat(store.tryRevoke("x", 60)).isFalse();
  }
}
