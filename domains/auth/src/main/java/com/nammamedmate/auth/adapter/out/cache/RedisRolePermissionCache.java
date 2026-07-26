package com.nammamedmate.auth.adapter.out.cache;

import com.nammamedmate.auth.application.port.out.RolePermissionCache;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Role-permission cache. When Redis is available, Redis is the sole source of truth so invalidate
 * on one instance is visible to all. Local map is only used when Redis is absent (unit tests /
 * local fallback).
 */
@Component
public final class RedisRolePermissionCache implements RolePermissionCache {

  private static final String KEY_PREFIX = "rbac:role:";

  private final StringRedisTemplate redis;
  private final ConcurrentHashMap<UUID, List<String>> local = new ConcurrentHashMap<>();

  public RedisRolePermissionCache(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis == null ? null : redis.getIfAvailable();
  }

  /** Local/unit-test fallback with no Redis. */
  public RedisRolePermissionCache() {
    this.redis = null;
  }

  @Override
  public void put(UUID roleId, List<String> permissions) {
    List<String> copy = List.copyOf(permissions);
    if (redis != null) {
      redis.opsForValue().set(KEY_PREFIX + roleId, String.join("\n", copy));
      return;
    }
    local.put(roleId, copy);
  }

  @Override
  public OptionalPermissions get(UUID roleId) {
    if (redis != null) {
      String raw = redis.opsForValue().get(KEY_PREFIX + roleId);
      if (raw != null) {
        List<String> perms = raw.isEmpty() ? List.of() : Arrays.asList(raw.split("\n", -1));
        return OptionalPermissions.hit(List.copyOf(perms));
      }
      return OptionalPermissions.miss();
    }
    List<String> cached = local.get(roleId);
    if (cached != null) {
      return OptionalPermissions.hit(cached);
    }
    return OptionalPermissions.miss();
  }

  @Override
  public void invalidate(UUID roleId) {
    if (redis != null) {
      redis.delete(KEY_PREFIX + roleId);
      return;
    }
    local.remove(roleId);
  }

  @Override
  public void putAll(Map<UUID, List<String>> entries) {
    entries.forEach(this::put);
  }
}
