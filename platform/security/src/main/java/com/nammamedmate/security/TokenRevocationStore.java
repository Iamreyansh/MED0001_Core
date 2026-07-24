package com.nammamedmate.security;

public interface TokenRevocationStore {

  boolean isRevoked(String jti);

  void revoke(String jti, long ttlSeconds);
}
