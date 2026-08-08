package com.nammamedmate.order.application.port.out;

import java.util.OptionalLong;
import java.util.UUID;

/** Delivery-zone membership for pharmacy checkout (PostGIS via apps/api bridge). */
public interface ZoneMembershipPort {

  boolean isInPharmacyZone(UUID pharmacyId, double lat, double lng);

  /**
   * Minimum cart total (paise) for the matched serviceable zone. Empty when address is not in zone.
   */
  default OptionalLong minOrderValuePaise(UUID pharmacyId, double lat, double lng) {
    return OptionalLong.empty();
  }
}
