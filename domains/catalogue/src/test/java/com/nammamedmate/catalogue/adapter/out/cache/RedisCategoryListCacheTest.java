package com.nammamedmate.catalogue.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisCategoryListCacheTest {

  @Test
  void localFallback_getPutInvalidate() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisCategoryListCache cache = new RedisCategoryListCache(provider);

    assertThat(cache.get()).isEmpty();
    cache.put("{\"ok\":true}");
    assertThat(cache.get()).contains("{\"ok\":true}");
    cache.invalidate();
    assertThat(cache.get()).isEmpty();
    cache.put(null);
  }

  @Test
  void redisPath() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(CategoryListCachePortKey())).thenReturn("cached");

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisCategoryListCache cache = new RedisCategoryListCache(provider);

    assertThat(cache.get()).isEqualTo(Optional.of("cached"));
    cache.put("json");
    verify(ops).set(eq("catalogue:categories:public"), eq("json"), eq(Duration.ofMinutes(5)));
    cache.invalidate();
    verify(redis).delete("catalogue:categories:public");
  }

  @Test
  void nullProvider() {
    RedisCategoryListCache cache = new RedisCategoryListCache(null);
    cache.put("x");
    assertThat(cache.get()).contains("x");
    cache.invalidate();
  }

  private static String CategoryListCachePortKey() {
    return "catalogue:categories:public";
  }
}
