package com.nammamedmate.order.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Thin rider directory until EPIC-011. */
public interface RiderLookupPort {

  record RiderInfo(UUID id, String name, String phone, String vehiclePlate, String avatarUrl) {}

  Optional<RiderInfo> findById(UUID riderId);

  default List<RiderInfo> listActive(int limit) {
    return List.of();
  }
}
