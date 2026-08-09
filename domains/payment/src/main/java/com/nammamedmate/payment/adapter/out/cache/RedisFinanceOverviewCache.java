package com.nammamedmate.payment.adapter.out.cache;

import com.nammamedmate.payment.application.port.out.FinanceOverviewCachePort;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisFinanceOverviewCache implements FinanceOverviewCachePort {

  private static final Duration TTL = Duration.ofSeconds(60);

  private final ObjectProvider<StringRedisTemplate> redis;
  private final AtomicReference<Cached> local = new AtomicReference<>();

  public RedisFinanceOverviewCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> getKpiJson() {
    StringRedisTemplate template = template();
    if (template != null) {
      return Optional.ofNullable(template.opsForValue().get(KPI_CACHE_KEY));
    }
    Cached cached = local.get();
    if (cached == null) {
      return Optional.empty();
    }
    if (cached.expiresAtMs() <= System.currentTimeMillis()) {
      local.compareAndSet(cached, null);
      return Optional.empty();
    }
    return Optional.of(cached.json());
  }

  @Override
  public void putKpiJson(String json) {
    if (json == null) {
      return;
    }
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().set(KPI_CACHE_KEY, json, TTL);
      return;
    }
    local.set(new Cached(json, System.currentTimeMillis() + TTL.toMillis()));
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }

  private record Cached(String json, long expiresAtMs) {}
}
