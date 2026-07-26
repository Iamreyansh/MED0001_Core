package com.nammamedmate.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.application.port.out.RolePermissionCache;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRolePermissionCacheTest {

  @Test
  void localFallbackRoundTrip() {
    RedisRolePermissionCache cache = new RedisRolePermissionCache();
    UUID id = UUID.randomUUID();
    assertThat(cache.get(id).present()).isFalse();
    cache.put(id, List.of("orders:read"));
    assertThat(cache.get(id).permissions()).containsExactly("orders:read");
    cache.invalidate(id);
    assertThat(cache.get(id).present()).isFalse();
  }

  @Test
  void redisBackedPutGetInvalidateReadsRedisEachTime() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);

    RedisRolePermissionCache cache = new RedisRolePermissionCache(provider);
    UUID id = UUID.randomUUID();
    cache.putAll(Map.of(id, List.of("a:b", "c:d")));
    verify(values).set(eq("rbac:role:" + id), eq("a:b\nc:d"));

    when(values.get("rbac:role:" + id)).thenReturn("a:b\nc:d");
    RolePermissionCache.OptionalPermissions hit = cache.get(id);
    assertThat(hit.permissions()).containsExactly("a:b", "c:d");
    verify(values).get("rbac:role:" + id);

    // Second get still hits Redis (no sticky local L1 when Redis is configured).
    when(values.get("rbac:role:" + id)).thenReturn("revoked-gone");
    assertThat(cache.get(id).permissions()).containsExactly("revoked-gone");

    cache.invalidate(id);
    verify(redis).delete("rbac:role:" + id);
    when(values.get("rbac:role:" + id)).thenReturn(null);
    assertThat(cache.get(id).present()).isFalse();

    when(values.get("rbac:role:" + id)).thenReturn("");
    assertThat(cache.get(id).permissions()).isEmpty();

    when(values.get(anyString())).thenReturn(null);
    assertThat(cache.get(UUID.randomUUID()).present()).isFalse();

    RedisRolePermissionCache cold = new RedisRolePermissionCache(provider);
    when(values.get("rbac:role:" + id)).thenReturn("from-redis");
    assertThat(cold.get(id).permissions()).containsExactly("from-redis");

    RedisRolePermissionCache nullProvider = new RedisRolePermissionCache(null);
    nullProvider.put(id, List.of("local-only"));
    assertThat(nullProvider.get(id).permissions()).containsExactly("local-only");
    // Local fallback must not touch Redis.
    verify(values, never()).set(eq("rbac:role:" + id), eq("local-only"));
  }
}
