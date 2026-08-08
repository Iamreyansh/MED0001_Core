package com.nammamedmate.rider.application.port.out;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface RiderLiveStatusCachePort {

  void put(UUID riderId, String status, Duration ttl);

  Optional<String> get(UUID riderId);

  void evict(UUID riderId);
}
