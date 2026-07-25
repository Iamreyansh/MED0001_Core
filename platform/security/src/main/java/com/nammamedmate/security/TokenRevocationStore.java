package com.nammamedmate.security;

public interface TokenRevocationStore {

  boolean isRevoked(String jti);

  void revoke(String jti, long ttlSeconds);

  /**
   * Atomically marks {@code jti} revoked if not already. Returns {@code true} for the first
   * successful consumer (single-use tokens); {@code false} if already revoked or args invalid.
   */
  boolean tryRevoke(String jti, long ttlSeconds);
}
