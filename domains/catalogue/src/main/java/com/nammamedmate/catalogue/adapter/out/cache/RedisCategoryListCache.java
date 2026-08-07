package com.nammamedmate.catalogue.adapter.out.cache;

import com.nammamedmate.catalogue.application.port.out.CategoryListCachePort;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisCategoryListCache implements CategoryListCachePort {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final ObjectProvider<StringRedisTemplate> redis;
  private final AtomicReference<String> local = new AtomicReference<>();

  public RedisCategoryListCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> get() {
    StringRedisTemplate template = template();
    if (template != null) {
      return Optional.ofNullable(template.opsForValue().get(CACHE_KEY));
    }
    return Optional.ofNullable(local.get());
  }

  @Override
  public void put(String json) {
    if (json == null) {
      return;
    }
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().set(CACHE_KEY, json, TTL);
      return;
    }
    local.set(json);
  }

  @Override
  public void invalidate() {
    StringRedisTemplate template = template();
    if (template != null) {
      template.delete(CACHE_KEY);
    }
    local.set(null);
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }
}
