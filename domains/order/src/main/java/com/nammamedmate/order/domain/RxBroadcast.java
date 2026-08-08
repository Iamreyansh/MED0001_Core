package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RxBroadcast(
    UUID id,
    UUID customerId,
    UUID prescriptionId,
    UUID deliveryAddressId,
    String patientName,
    String notes,
    List<RequestedMedicine> medicinesRequested,
    RxBroadcastStatus status,
    int pharmaciesNotified,
    Instant broadcastAt,
    Instant expiresAt,
    UUID selectedPharmacyId,
    UUID resultingCartId,
    Instant createdAt) {

  public record RequestedMedicine(String name, int quantity) {}

  public RxBroadcast {
    medicinesRequested = medicinesRequested == null ? List.of() : List.copyOf(medicinesRequested);
  }
}
