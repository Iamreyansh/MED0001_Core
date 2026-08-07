package com.nammamedmate.catalogue.adapter.out.cache;

import com.nammamedmate.catalogue.application.port.out.SearchCachePort;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSearchCache implements SearchCachePort {

  static final Duration AC_TTL = Duration.ofMinutes(10);
  static final Duration DETAIL_TTL = Duration.ofMinutes(5);

  private final ObjectProvider<StringRedisTemplate> redis;
  private final Map<String, String> local = new ConcurrentHashMap<>();

  public RedisSearchCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> getAutocomplete(String normalizedQuery) {
    return get(acKey(normalizedQuery));
  }

  @Override
  public void putAutocomplete(String normalizedQuery, String json) {
    put(acKey(normalizedQuery), json, AC_TTL);
  }

  @Override
  public Optional<String> getMedicineDetail(UUID medicineId) {
    return get(detailKey(medicineId));
  }

  @Override
  public void putMedicineDetail(UUID medicineId, String json) {
    put(detailKey(medicineId), json, DETAIL_TTL);
  }

  private Optional<String> get(String key) {
    StringRedisTemplate template = template();
    if (template != null) {
      return Optional.ofNullable(template.opsForValue().get(key));
    }
    return Optional.ofNullable(local.get(key));
  }

  private void put(String key, String json, Duration ttl) {
    if (json == null) {
      return;
    }
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().set(key, json, ttl);
      return;
    }
    local.put(key, json);
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }

  static String acKey(String normalizedQuery) {
    return "catalogue:ac:" + normalizedQuery;
  }

  static String detailKey(UUID medicineId) {
    return "catalogue:medicine:" + medicineId;
  }
}
