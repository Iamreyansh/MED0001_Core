package com.nammamedmate.payment.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.payment.application.port.out.FinanceOverviewCachePort;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisFinanceOverviewCacheTest {

  @Test
  @SuppressWarnings("unchecked")
  void redisGetAndPut() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(FinanceOverviewCachePort.KPI_CACHE_KEY)).thenReturn("{\"a\":1}");
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);

    RedisFinanceOverviewCache cache = new RedisFinanceOverviewCache(provider);
    assertThat(cache.getKpiJson()).contains("{\"a\":1}");
    cache.putKpiJson("{\"b\":2}");
    verify(ops)
        .set(
            eq(FinanceOverviewCachePort.KPI_CACHE_KEY),
            eq("{\"b\":2}"),
            eq(Duration.ofSeconds(60)));
  }

  @Test
  void localFallbackWhenRedisUnavailable() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisFinanceOverviewCache cache = new RedisFinanceOverviewCache(provider);
    assertThat(cache.getKpiJson()).isEmpty();
    cache.putKpiJson(null);
    assertThat(cache.getKpiJson()).isEmpty();
    cache.putKpiJson("{\"x\":1}");
    assertThat(cache.getKpiJson()).contains("{\"x\":1}");
  }

  @Test
  @SuppressWarnings("unchecked")
  void localCacheExpires() throws Exception {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisFinanceOverviewCache cache = new RedisFinanceOverviewCache(provider);
    cache.putKpiJson("stale");
    var field = RedisFinanceOverviewCache.class.getDeclaredField("local");
    field.setAccessible(true);
    AtomicReference<Object> local = (AtomicReference<Object>) field.get(cache);
    Object cached = local.get();
    var ctor = cached.getClass().getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    local.set(ctor.newInstance("stale", System.currentTimeMillis() - 1));
    assertThat(cache.getKpiJson()).isEmpty();
  }

  @Test
  void nullProviderUsesLocal() {
    RedisFinanceOverviewCache cache = new RedisFinanceOverviewCache(null);
    cache.putKpiJson("ok");
    assertThat(cache.getKpiJson()).contains("ok");
  }
}
