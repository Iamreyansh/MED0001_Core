package com.nammamedmate.order.application.port.out;

import java.util.UUID;

/** Zone check until pharmacy zone wiring is reused — stub may always return in-zone. */
public interface ZoneMembershipPort {

  boolean isInPharmacyZone(UUID pharmacyId, double lat, double lng);
}
