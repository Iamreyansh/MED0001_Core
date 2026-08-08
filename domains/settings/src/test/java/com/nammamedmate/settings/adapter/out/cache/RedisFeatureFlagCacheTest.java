package com.nammamedmate.settings.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisFeatureFlagCacheTest {

  @Test
  void localFallbackGetPutInvalidate() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisFeatureFlagCache cache = new RedisFeatureFlagCache(provider, new ObjectMapper());

    assertThat(cache.get("production")).isEmpty();
    cache.put("production", Map.of("cod_enabled", true));
    assertThat(cache.get("production")).contains(Map.of("cod_enabled", true));
    cache.invalidate("production");
    assertThat(cache.get("production")).isEmpty();
    cache.put(null, Map.of("x", true));
    cache.put("staging", null);
    cache.invalidate(null);
  }

  @Test
  void redisPathAndBadJson() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("feature_flags:production")).thenReturn("{\"cod_enabled\":true}");

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisFeatureFlagCache cache = new RedisFeatureFlagCache(provider, new ObjectMapper());

    assertThat(cache.get("production")).contains(Map.of("cod_enabled", true));
    cache.put("production", Map.of("cod_enabled", false));
    verify(ops)
        .set(
            eq("feature_flags:production"),
            eq("{\"cod_enabled\":false}"),
            eq(Duration.ofSeconds(60)));
    cache.invalidate("production");
    verify(redis).delete("feature_flags:production");

    when(ops.get("feature_flags:staging")).thenReturn("not-json");
    assertThat(cache.get("staging")).isEmpty();
    when(ops.get("feature_flags:development")).thenReturn(" ");
    assertThat(cache.get("development")).isEmpty();
  }

  @Test
  void nullProvider() {
    RedisFeatureFlagCache cache = new RedisFeatureFlagCache(null, new ObjectMapper());
    cache.put("production", Map.of("a", true));
    assertThat(cache.get("production")).contains(Map.of("a", true));
    cache.invalidate("production");
  }

  @Test
  void putSwallowsMapperFailure() throws Exception {
    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisFeatureFlagCache cache = new RedisFeatureFlagCache(provider, mapper);
    cache.put("production", Map.of("a", true));
    assertThat(cache.get("production")).isEmpty();
  }
}
