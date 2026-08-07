package com.nammamedmate.catalogue.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisSearchCacheTest {

  @Test
  void localFallback() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisSearchCache cache = new RedisSearchCache(provider);
    UUID id = UUID.randomUUID();

    assertThat(cache.getAutocomplete("aug")).isEmpty();
    cache.putAutocomplete("aug", "[1]");
    assertThat(cache.getAutocomplete("aug")).contains("[1]");
    cache.putMedicineDetail(id, "{}");
    assertThat(cache.getMedicineDetail(id)).contains("{}");
    cache.putAutocomplete(null, "x");
    cache.putMedicineDetail(id, null);
  }

  @Test
  void redisPath() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("catalogue:ac:q")).thenReturn("ac");
    UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
    when(ops.get("catalogue:medicine:" + id)).thenReturn("detail");

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisSearchCache cache = new RedisSearchCache(provider);

    assertThat(cache.getAutocomplete("q")).contains("ac");
    assertThat(cache.getMedicineDetail(id)).contains("detail");
    cache.putAutocomplete("q", "ac2");
    verify(ops).set(eq("catalogue:ac:q"), eq("ac2"), eq(Duration.ofMinutes(10)));
    cache.putMedicineDetail(id, "d2");
    verify(ops).set(eq("catalogue:medicine:" + id), eq("d2"), eq(Duration.ofMinutes(5)));
  }

  @Test
  void nullProvider() {
    RedisSearchCache cache = new RedisSearchCache(null);
    cache.putAutocomplete("a", "b");
    assertThat(cache.getAutocomplete("a")).contains("b");
  }
}
