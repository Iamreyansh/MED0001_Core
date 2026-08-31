package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.RiderLookupPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ponytail: stub until EPIC-011 rider registry; synthesizes display fields from id. */
public class StubRiderLookupAdapter implements RiderLookupPort {

  @Override
  public Optional<RiderInfo> findById(UUID riderId) {
    if (riderId == null) {
      return Optional.empty();
    }
    String shortId = riderId.toString().substring(0, 8);
    return Optional.of(
        new RiderInfo(
            riderId,
            "Rider " + shortId,
            "+91-9000000000",
            "KA01XX" + shortId.substring(0, 4).toUpperCase(),
            null));
  }

  @Override
  public List<RiderInfo> listActive(int limit) {
    int cap = Math.max(limit, 0);
    if (cap == 0) {
      return List.of();
    }
    return findById(UUID.fromString("dddddddd-0001-4000-8000-000000000001")).stream()
        .limit(cap)
        .toList();
  }
}
