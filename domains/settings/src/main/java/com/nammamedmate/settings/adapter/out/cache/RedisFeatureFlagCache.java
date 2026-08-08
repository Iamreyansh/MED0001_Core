package com.nammamedmate.settings.adapter.out.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.settings.application.port.out.FeatureFlagCachePort;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisFeatureFlagCache implements FeatureFlagCachePort {

  static final Duration TTL = Duration.ofSeconds(60);
  static final String KEY_PREFIX = "feature_flags:";

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ObjectMapper objectMapper;
  private final Map<String, String> local = new ConcurrentHashMap<>();

  public RedisFeatureFlagCache(
      ObjectProvider<StringRedisTemplate> redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<Map<String, Boolean>> get(String environment) {
    String raw = read(key(environment));
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(raw, new TypeReference<Map<String, Boolean>>() {}));
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  @Override
  public void put(String environment, Map<String, Boolean> enabledByName) {
    if (environment == null || enabledByName == null) {
      return;
    }
    try {
      write(key(environment), objectMapper.writeValueAsString(enabledByName));
    } catch (Exception ignored) {
      // ponytail: cache miss is fine; next poll reloads from DB
    }
  }

  @Override
  public void invalidate(String environment) {
    if (environment == null) {
      return;
    }
    String k = key(environment);
    StringRedisTemplate template = template();
    if (template != null) {
      template.delete(k);
    }
    local.remove(k);
  }

  private String read(String key) {
    StringRedisTemplate template = template();
    if (template != null) {
      return template.opsForValue().get(key);
    }
    return local.get(key);
  }

  private void write(String key, String json) {
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().set(key, json, TTL);
      return;
    }
    local.put(key, json);
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }

  static String key(String environment) {
    return KEY_PREFIX + environment;
  }
}
