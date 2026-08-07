package com.nammamedmate.pharmacy.adapter.out.cache;

import com.nammamedmate.pharmacy.application.port.out.ZonePharmacyCachePort;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisZonePharmacyCacheClient implements ZonePharmacyCachePort {

  private final ObjectProvider<StringRedisTemplate> redis;
  private final Set<UUID> localInvalidated = ConcurrentHashMap.newKeySet();

  public RedisZonePharmacyCacheClient(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public void invalidate(UUID zoneId) {
    if (zoneId == null) {
      return;
    }
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template != null) {
      template.delete(cacheKey(zoneId));
    } else {
      localInvalidated.add(zoneId);
    }
  }

  /** Test hook — whether invalidate was called without Redis. */
  public boolean wasInvalidatedLocally(UUID zoneId) {
    return localInvalidated.contains(zoneId);
  }

  public static String cacheKey(UUID zoneId) {
    return "zone:" + zoneId + ":pharmacies";
  }
}
