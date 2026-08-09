package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import java.time.Instant;
import java.util.Optional;

public interface GeocodeCacheStore {

  Optional<GeocodeCacheEntry> findValid(String cacheKey, Instant now);

  void upsert(GeocodeCacheEntry entry);
}
