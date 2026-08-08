package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RxBroadcastPharmacy(
    UUID id,
    UUID broadcastId,
    UUID pharmacyId,
    double distanceKm,
    RxPharmacySlotStatus status,
    List<QuotedMedicine> medicinesAvailable,
    Integer deliveryEtaMinutes,
    Long totalPayablePaise,
    Instant receivedAt,
    Instant responseDeadline,
    Instant quotedAt,
    Instant quoteExpiresAt,
    List<String> tags) {

  public RxBroadcastPharmacy {
    medicinesAvailable = medicinesAvailable == null ? null : List.copyOf(medicinesAvailable);
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  public boolean quoteExpired(Instant now) {
    return quoteExpiresAt != null && !now.isBefore(quoteExpiresAt);
  }
}
