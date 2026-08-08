package com.nammamedmate.settings.application.port.out;

import java.util.Map;
import java.util.Optional;

/** Redis (or local fallback) cache of base enabled states keyed by environment. TTL 60s. */
public interface FeatureFlagCachePort {

  Optional<Map<String, Boolean>> get(String environment);

  void put(String environment, Map<String, Boolean> enabledByName);

  void invalidate(String environment);
}
