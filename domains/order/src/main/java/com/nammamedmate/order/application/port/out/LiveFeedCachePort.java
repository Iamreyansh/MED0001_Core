package com.nammamedmate.order.application.port.out;

import java.util.Optional;

/** Redis-backed live-feed cache (10s TTL). */
public interface LiveFeedCachePort {

  Optional<String> get(String key);

  void put(String key, String json, java.time.Duration ttl);
}
