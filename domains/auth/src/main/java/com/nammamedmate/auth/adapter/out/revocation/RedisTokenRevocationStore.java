package com.nammamedmate.auth.adapter.out.revocation;

import com.nammamedmate.security.TokenRevocationStore;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisTokenRevocationStore implements TokenRevocationStore {

  private static final String PREFIX = "auth:revoked:";

  private final StringRedisTemplate redis;

  public RedisTokenRevocationStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public boolean isRevoked(String jti) {
    if (jti == null || jti.isBlank()) {
      return true;
    }
    Boolean exists = redis.hasKey(key(jti));
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public void revoke(String jti, long ttlSeconds) {
    if (jti == null || jti.isBlank() || ttlSeconds < 1) {
      return;
    }
    redis.opsForValue().set(key(jti), "1", Duration.ofSeconds(ttlSeconds));
  }

  @Override
  public boolean tryRevoke(String jti, long ttlSeconds) {
    if (jti == null || jti.isBlank() || ttlSeconds < 1) {
      return false;
    }
    Boolean created =
        redis.opsForValue().setIfAbsent(key(jti), "1", Duration.ofSeconds(ttlSeconds));
    return Boolean.TRUE.equals(created);
  }

  private static String key(String jti) {
    return PREFIX + jti;
  }
}
