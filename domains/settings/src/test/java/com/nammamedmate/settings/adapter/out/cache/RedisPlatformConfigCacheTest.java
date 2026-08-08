package com.nammamedmate.settings.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.ConfigRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisPlatformConfigCacheTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");

  @Test
  void localFallbackGetPutInvalidate() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisPlatformConfigCache cache = new RedisPlatformConfigCache(provider, new ObjectMapper());

    assertThat(cache.getAll()).isEmpty();
    ConfigRow row = sample(null);
    cache.putAll(List.of(row));
    assertThat(cache.getAll()).isPresent();
    assertThat(cache.getAll().get().get(0).key()).isEqualTo("orders.delivery_fee");
    cache.invalidate();
    assertThat(cache.getAll()).isEmpty();
    cache.putAll(null);
  }

  @Test
  void redisPathAndBadJson() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    UUID by = Ids.newId();
    String json =
        "[{\"key\":\"orders.delivery_fee\",\"value\":\"25\",\"type\":\"integer\",\"unit\":\"INR\","
            + "\"domain\":\"orders\",\"immutable\":false,\"description\":\"d\",\"updated_by\":\""
            + by
            + "\",\"updated_at\":\""
            + NOW
            + "\"}]";
    when(ops.get("platform_config")).thenReturn(json);

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisPlatformConfigCache cache = new RedisPlatformConfigCache(provider, new ObjectMapper());

    assertThat(cache.getAll()).isPresent();
    assertThat(cache.getAll().get().get(0).updatedBy()).isEqualTo(by);
    cache.putAll(List.of(sample(by)));
    verify(ops).set(eq("platform_config"), any(String.class), eq(Duration.ofSeconds(60)));
    cache.invalidate();
    verify(redis).delete("platform_config");

    when(ops.get("platform_config")).thenReturn("not-json");
    assertThat(cache.getAll()).isEmpty();
    when(ops.get("platform_config")).thenReturn(" ");
    assertThat(cache.getAll()).isEmpty();
  }

  @Test
  void nullProviderAndMapperFailure() throws Exception {
    RedisPlatformConfigCache cache = new RedisPlatformConfigCache(null, new ObjectMapper());
    cache.putAll(List.of(sample(null)));
    assertThat(cache.getAll()).isPresent();
    cache.invalidate();

    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisPlatformConfigCache failing = new RedisPlatformConfigCache(provider, mapper);
    failing.putAll(List.of(sample(null)));
    assertThat(failing.getAll()).isEmpty();
  }

  @Test
  void fromMapImmutableStringAndBlankIds() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    String json =
        "[{\"key\":\"k\",\"value\":\"v\",\"type\":\"string\",\"unit\":null,\"domain\":\"orders\","
            + "\"immutable\":\"true\",\"description\":\"d\",\"updated_by\":\"\",\"updated_at\":\"\"}]";
    when(ops.get("platform_config")).thenReturn(json);
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisPlatformConfigCache cache = new RedisPlatformConfigCache(provider, new ObjectMapper());
    assertThat(cache.getAll()).isPresent();
    assertThat(cache.getAll().get().get(0).immutable()).isTrue();
    assertThat(cache.getAll().get().get(0).updatedBy()).isNull();
    assertThat(cache.getAll().get().get(0).updatedAt()).isNull();
  }

  @Test
  void toMapNullTimestampsAndBooleanImmutable() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisPlatformConfigCache cache = new RedisPlatformConfigCache(provider, new ObjectMapper());
    ConfigRow row =
        new ConfigRow(
            "orders.delivery_fee", "25", "integer", "INR", "orders", true, "d", null, null);
    cache.putAll(List.of(row));
    assertThat(cache.getAll().get().get(0).updatedAt()).isNull();
    assertThat(cache.getAll().get().get(0).immutable()).isTrue();
  }

  private static ConfigRow sample(UUID updatedBy) {
    return new ConfigRow(
        "orders.delivery_fee", "25", "integer", "INR", "orders", false, "d", updatedBy, NOW);
  }
}
