package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.GovernmentVerificationCacheEntry;
import java.time.Instant;
import java.util.Optional;

public interface GovernmentVerificationCacheStore {

  Optional<GovernmentVerificationCacheEntry> findValid(
      String verificationType, String identifier, String state, Instant now);

  void upsert(GovernmentVerificationCacheEntry entry);
}
